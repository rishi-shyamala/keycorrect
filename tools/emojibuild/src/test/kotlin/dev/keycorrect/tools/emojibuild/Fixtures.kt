package dev.keycorrect.tools.emojibuild

/** A miniature emoji-test.txt in the real file's format. */
internal val EMOJI_TEST_FIXTURE =
    """
    # emoji-test.txt
    # Version: 17.0
    #
    # group: Smileys & Emotion

    # subgroup: face-smiling
    1F600                                                  ; fully-qualified     # 😀 E1.0 grinning face
    2764 FE0F                                              ; fully-qualified     # ❤️ E0.6 red heart
    2764                                                   ; unqualified         # ❤ E0.6 red heart

    # group: People & Body

    # subgroup: hand-fingers-closed
    1F44D                                                  ; fully-qualified     # 👍 E0.6 thumbs up
    1F44D 1F3FB                                            ; fully-qualified     # 👍🏻 E1.0 thumbs up: light skin tone
    1F44D 1F3FC                                            ; fully-qualified     # 👍🏼 E1.0 thumbs up: medium-light skin tone
    1F44D 1F3FD                                            ; fully-qualified     # 👍🏽 E1.0 thumbs up: medium skin tone
    1F44D 1F3FE                                            ; fully-qualified     # 👍🏾 E1.0 thumbs up: medium-dark skin tone
    1F44D 1F3FF                                            ; fully-qualified     # 👍🏿 E1.0 thumbs up: dark skin tone
    1F91D                                                  ; fully-qualified     # 🤝 E3.0 handshake
    1FAF1 1F3FB 200D 1FAF2 1F3FC                           ; fully-qualified     # 🫱🏻‍🫲🏼 E14.0 handshake: light skin tone, medium-light skin tone
    1F468                                                  ; fully-qualified     # 👨 E0.6 man
    1F469                                                  ; fully-qualified     # 👩 E0.6 woman
    1F9D1 200D 2695 FE0F                                   ; fully-qualified     # 🧑‍⚕️ E12.1 health worker
    1F468 200D 2695 FE0F                                   ; fully-qualified     # 👨‍⚕️ E4.0 man health worker
    1F468 200D 2695                                        ; minimally-qualified # 👨‍⚕ E4.0 man health worker
    1F469 200D 2695 FE0F                                   ; fully-qualified     # 👩‍⚕️ E4.0 woman health worker
    1F3C3                                                  ; fully-qualified     # 🏃 E0.6 person running
    1F3C3 1F3FB                                            ; fully-qualified     # 🏃🏻 E1.0 person running: light skin tone
    1F3C3 200D 2642 FE0F                                   ; fully-qualified     # 🏃‍♂️ E4.0 man running
    1F3C3 1F3FB 200D 2642 FE0F                             ; fully-qualified     # 🏃🏻‍♂️ E4.0 man running: light skin tone
    1F3C3 200D 2640 FE0F                                   ; fully-qualified     # 🏃‍♀️ E4.0 woman running
    1F3C3 200D 27A1 FE0F                                   ; fully-qualified     # 🏃‍➡️ E15.1 person running facing right
    1F3C3 200D 2642 FE0F 200D 27A1 FE0F                    ; fully-qualified     # 🏃‍♂️‍➡️ E15.1 man running facing right
    1F46F 200D 2642 FE0F                                   ; fully-qualified     # 👯‍♂️ E4.0 men with bunny ears

    # group: Component

    1F3FB                                                  ; component           # 🏻 E1.0 light skin tone
    1F9B0                                                  ; component           # 🦰 E11.0 red hair

    # group: Food & Drink

    1F355                                                  ; fully-qualified     # 🍕 E0.6 pizza
    """
        .trimIndent()
