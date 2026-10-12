package dev.keycorrect.tools.emojibuild

import dev.keycorrect.core.emoji.KaomojiGroup

/**
 * Common kaomoji (text emoticons), written for KeyCorrect. They are short, widely used typographic
 * arrangements; no third-party list was copied.
 */
object Kaomoji {
    val groups: List<KaomojiGroup> =
        listOf(
                KaomojiGroup(
                    "Happy",
                    listOf(
                        "(^_^)",
                        "(^‿^)",
                        "(＾▽＾)",
                        "(◕‿◕)",
                        "(≧▽≦)",
                        "(*^▽^*)",
                        "(｡◕‿◕｡)",
                        "(✿◠‿◠)",
                        "\\(^o^)/",
                        "ヽ(^o^)ノ",
                        "٩(◕‿◕)۶",
                        "(ᵔᴥᵔ)",
                        "(^∇^)",
                        "(＾ω＾)",
                        "(•‿•)",
                        "ヽ(´▽`)/",
                    ),
                ),
                KaomojiGroup(
                    "Love",
                    listOf(
                        "(♥‿♥)",
                        "(｡♥‿♥｡)",
                        "(⁄ ⁄>⁄ ▽ ⁄<⁄ ⁄)",
                        "(*^3^)/~☆",
                        "(´∩｡• ᵕ •｡∩`)♡",
                        "(◍•ᴗ•◍)❤",
                        "(づ｡◕‿‿◕｡)づ",
                        "(つ≧▽≦)つ",
                        "(´,,•ω•,,)♡",
                        "♡(˃͈ ˂͈ )",
                        "(｡・//ε//・｡)",
                        "( ˘ ³˘)♥",
                        "(•ε•)♡",
                        "(´• ω •`)♡",
                    ),
                ),
                KaomojiGroup(
                    "Sad",
                    listOf(
                        "(T_T)",
                        "(;_;)",
                        "(╥_╥)",
                        "(ಥ_ಥ)",
                        "(´;ω;`)",
                        "(；＿；)",
                        "(つД｀)",
                        "(｡•́︿•̀｡)",
                        "(._.)",
                        "(ノД`)",
                        "( ; ; )",
                        "(╯︵╰,)",
                        "(´•̥̥̥ω•̥̥̥`)",
                    ),
                ),
                KaomojiGroup(
                    "Angry",
                    listOf(
                        "(╬ಠ益ಠ)",
                        "(ಠ_ಠ)",
                        "(`Д´)",
                        "(＃｀Д´)",
                        "(¬_¬)",
                        "(눈_눈)",
                        "(｀ε´)",
                        "(>_<)",
                        "(ノಠ益ಠ)ノ",
                        "ヽ(`Д´)ﾉ",
                        "(╯°□°)╯",
                        "(҂`_´)",
                        "(≖_≖)",
                    ),
                ),
                KaomojiGroup(
                    "Surprised",
                    listOf(
                        "(⊙_⊙)",
                        "(°o°)",
                        "(O_O)",
                        "(⊙﹏⊙)",
                        "(°ロ°)",
                        "Σ(°△°|||)",
                        "(ﾟДﾟ)",
                        "(◎_◎)",
                        "w(°o°)w",
                        "(o_O)",
                    ),
                ),
                KaomojiGroup(
                    "Shy",
                    listOf(
                        "(⁄ ⁄•⁄ω⁄•⁄ ⁄)",
                        "(〃ω〃)",
                        "(*/ω＼)",
                        "(/▽＼)",
                        "(´▽`ʃ♡ƪ)",
                        "(๑•́ ₃ •̀๑)",
                    ),
                ),
                KaomojiGroup(
                    "Actions",
                    listOf(
                        "¯\\_(ツ)_/¯",
                        "(╯°□°）╯︵ ┻━┻",
                        "┬─┬ノ( º _ ºノ)",
                        "(ノ^_^)ノ",
                        "(^_^)/",
                        "m(_ _)m",
                        "(=^･ω･^=)",
                        "(☞ﾟヮﾟ)☞",
                        "☜(ﾟヮﾟ☜)",
                        "(づ￣ ³￣)づ",
                        "o(*°▽°*)o",
                        "(ง'̀-'́)ง",
                        "ᕕ( ᐛ )ᕗ",
                        "(•_•)",
                        "( •_•)>⌐■-■",
                        "(⌐■_■)",
                        "(ﾉ◕ヮ◕)ﾉ*:･ﾟ✧",
                        "(☞⌐■_■)☞",
                        "ヾ(⌐■_■)ノ♪",
                        "(-_-)zzz",
                        "(－ω－) zzZ",
                        "┏(＾0＾)┛",
                    ),
                ),
                KaomojiGroup(
                    "Animals",
                    listOf(
                        "(=^･ω･^=)",
                        "ʕ•ᴥ•ʔ",
                        "(◕ᴥ◕ʋ)",
                        "U・ᴥ・U",
                        "(=ↀωↀ=)",
                        "(^・ω・^)",
                        "(・ω・)ノ",
                        "ଲ(ⓛ ω ⓛ)ଲ",
                        "(ᵔᴥᵔ)",
                        "~(˘▾˘~)",
                        "(๑˃ᴗ˂)ﻭ",
                        "<(｀^´)>",
                    ),
                ),
                KaomojiGroup(
                    "Other",
                    listOf(
                        "( ͡° ͜ʖ ͡°)",
                        "(¬‿¬)",
                        "(≖‿≖)",
                        "(¬_¬\")",
                        "(._.) ",
                        "(´･_･`)",
                        "(-.-)",
                        "(ー_ー)!!",
                        "(・_・;)",
                        "(^^;)",
                        "(´∀`)",
                        "(*´▽`*)",
                        "(￣▽￣)",
                        "orz",
                    ),
                ),
            )
            .distinctItems()

    /** Drops repeated kaomoji, keeping the first group that lists each. */
    private fun List<KaomojiGroup>.distinctItems(): List<KaomojiGroup> {
        val seen = HashSet<String>()
        return map { g -> KaomojiGroup(g.name, g.items.map { it.trim() }.filter { seen.add(it) }) }
    }
}
