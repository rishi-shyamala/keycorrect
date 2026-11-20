// JNI glue between dev.keycorrect.core.swipenet.SwipeNetNative and ExecuTorch: runs FUTO Swipe's
// encoder (a .pte with an XNNPACK delegate) on one gesture. Built into libkcswipenet.so together
// with the ExecuTorch runtime, its selected portable kernels and XNNPACK, all linked statically
// (tools/swipenet/build.sh). Nothing here logs user data.
//
// The model's interface (docs/PHASE10.md, 10a-2):
//   inputs   features     f32  [1, 2, 64]   all 64 x, then all 64 y, in [0, 1]
//            layout_keys  f32  [1, 64, 2]   key centres in the same frame, padding (0, 0)
//            layout_mask  bool [1, 64]      true for the real keys
//   outputs  log_emissions [1, 32, 65], coefficients [1, 32, 64], lambda [1, 32, 1]
// The three outputs are copied, in that order, into one float array.

#include <jni.h>

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <vector>

#include <executorch/extension/data_loader/buffer_data_loader.h>
#include <executorch/extension/module/module.h>
#include <executorch/extension/tensor/tensor.h>
#include <executorch/extension/threadpool/threadpool.h>
#include <executorch/runtime/platform/runtime.h>

using executorch::aten::ScalarType;
using executorch::extension::BufferDataLoader;
using executorch::extension::Module;
using executorch::extension::from_blob;
using executorch::runtime::Error;
using executorch::runtime::EValue;

namespace {

constexpr int kPoints = 64;
constexpr int kMaxKeys = 64;
constexpr int kFeatures = 2 * kPoints;
constexpr int kKeyFloats = 2 * kMaxKeys;
// Output sizes, checked against the model when it is opened.
constexpr int kSteps = 32;
constexpr int kEmissions = kSteps * (kMaxKeys + 1);
constexpr int kCoefficients = kSteps * 64;
constexpr int kLambda = kSteps;
constexpr int kOutput = kEmissions + kCoefficients + kLambda;
constexpr size_t kOutputSizes[] = {kEmissions, kCoefficients, kLambda};
// Program data is read in place; constant segments expect an aligned base.
constexpr size_t kAlignment = 64;

struct FreeDeleter {
  void operator()(uint8_t* p) const { std::free(p); }
};

// One loaded model. The buffer must outlive the module, so it is declared first.
struct Encoder {
  std::unique_ptr<uint8_t, FreeDeleter> bytes;
  std::unique_ptr<Module> module;
  float features[kFeatures];
  float keys[kKeyFloats];
  bool mask[kMaxKeys];

  // Runs forward on the staged inputs; copies the outputs into out (kOutput floats).
  bool run(float* out) {
    auto f = from_blob(features, {1, 2, kPoints}, ScalarType::Float);
    auto k = from_blob(keys, {1, kMaxKeys, 2}, ScalarType::Float);
    auto m = from_blob(mask, {1, kMaxKeys}, ScalarType::Bool);
    std::vector<EValue> inputs;
    inputs.reserve(3);
    inputs.emplace_back(f);
    inputs.emplace_back(k);
    inputs.emplace_back(m);
    auto result = module->forward(inputs);
    if (!result.ok()) return false;
    const auto& outs = result.get();
    if (outs.size() != 3) return false;
    size_t offset = 0;
    for (size_t i = 0; i < 3; ++i) {
      if (!outs[i].isTensor()) return false;
      const auto& t = outs[i].toTensor();
      if (t.scalar_type() != ScalarType::Float ||
          static_cast<size_t>(t.numel()) != kOutputSizes[i]) {
        return false;
      }
      std::memcpy(out + offset, t.const_data_ptr<float>(), kOutputSizes[i] * sizeof(float));
      offset += kOutputSizes[i];
    }
    return true;
  }
};

Encoder* fromHandle(jlong handle) { return reinterpret_cast<Encoder*>(handle); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_dev_keycorrect_core_swipenet_SwipeNetNative_open(
    JNIEnv* env, jobject, jbyteArray model, jint threads) {
  if (model == nullptr) return 0;
  const jsize size = env->GetArrayLength(model);
  if (size <= 0) return 0;
  executorch::runtime::runtime_init();
  if (threads > 0) {
    if (auto* pool = executorch::extension::threadpool::get_threadpool()) {
      // The pool is process-wide; the encoder is the only user in this library.
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
      pool->_unsafe_reset_threadpool(static_cast<uint32_t>(threads));
#pragma GCC diagnostic pop
    }
  }
  const size_t rounded = (static_cast<size_t>(size) + kAlignment - 1) / kAlignment * kAlignment;
  std::unique_ptr<uint8_t, FreeDeleter> bytes(
      static_cast<uint8_t*>(std::aligned_alloc(kAlignment, rounded)));
  if (!bytes) return 0;
  env->GetByteArrayRegion(model, 0, size, reinterpret_cast<jbyte*>(bytes.get()));
  if (env->ExceptionCheck()) return 0;

  auto encoder = std::make_unique<Encoder>();
  auto loader = std::make_unique<BufferDataLoader>(bytes.get(), static_cast<size_t>(size));
  encoder->bytes = std::move(bytes);
  encoder->module = std::make_unique<Module>(std::move(loader));
  if (encoder->module->load_forward() != Error::Ok) return 0;

  // One dry run on padding checks the outputs' types and sizes before anybody relies on them.
  std::memset(encoder->features, 0, sizeof(encoder->features));
  std::memset(encoder->keys, 0, sizeof(encoder->keys));
  for (bool& m : encoder->mask) m = true;
  std::vector<float> out(kOutput);
  if (!encoder->run(out.data())) return 0;
  return reinterpret_cast<jlong>(encoder.release());
}

JNIEXPORT jboolean JNICALL Java_dev_keycorrect_core_swipenet_SwipeNetNative_run(
    JNIEnv* env, jobject, jlong handle, jfloatArray features, jfloatArray keys, jbyteArray mask,
    jfloatArray out) {
  Encoder* encoder = fromHandle(handle);
  if (encoder == nullptr || features == nullptr || keys == nullptr || mask == nullptr ||
      out == nullptr) {
    return JNI_FALSE;
  }
  if (env->GetArrayLength(features) != kFeatures || env->GetArrayLength(keys) != kKeyFloats ||
      env->GetArrayLength(mask) != kMaxKeys || env->GetArrayLength(out) != kOutput) {
    return JNI_FALSE;
  }
  env->GetFloatArrayRegion(features, 0, kFeatures, encoder->features);
  env->GetFloatArrayRegion(keys, 0, kKeyFloats, encoder->keys);
  jbyte bytes[kMaxKeys];
  env->GetByteArrayRegion(mask, 0, kMaxKeys, bytes);
  if (env->ExceptionCheck()) return JNI_FALSE;
  for (int i = 0; i < kMaxKeys; ++i) encoder->mask[i] = bytes[i] != 0;

  float result[kOutput];
  if (!encoder->run(result)) return JNI_FALSE;
  env->SetFloatArrayRegion(out, 0, kOutput, result);
  return env->ExceptionCheck() ? JNI_FALSE : JNI_TRUE;
}

JNIEXPORT void JNICALL Java_dev_keycorrect_core_swipenet_SwipeNetNative_close(JNIEnv*, jobject,
                                                                             jlong handle) {
  delete fromHandle(handle);
}

}  // extern "C"
