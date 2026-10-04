package space.akiba.screen_node

/**
 * Пиксельные спрайты сцен, заданные строками: каждый символ — пиксель, цвет берётся из палитры
 * [SPRITE_PALETTE], '.' и символы вне палитры — прозрачные.
 */

internal val SPRITE_PALETTE = mapOf(
    'w' to 0xFFE8E8E8.toInt(), 'W' to 0xFFFFFFFF.toInt(), 'g' to 0xFF9AA0A6.toInt(),
    'd' to 0xFF5A5F66.toInt(), 'k' to 0xFF26262B.toInt(), 'r' to 0xFFBC273A.toInt(),
    'R' to 0xFFFF3B53.toInt(), 'y' to 0xFFFFD23F.toInt(), 'o' to 0xFFFF8C1A.toInt(),
    'b' to 0xFF3D5AFE.toInt(), 'B' to 0xFF2121FF.toInt(), 'c' to 0xFF36E2FF.toInt(),
    'p' to 0xFFFF9EC4.toInt(), 'G' to 0xFF33DD66.toInt(), 'n' to 0xFF8B5A2B.toInt(),
    'f' to 0xFFFFC8A0.toInt(), 'm' to 0xFFC04DFF.toInt(),
)

private fun sprite(vararg rows: String) = PixelSprite(rows.toList())

internal object SceneSprites {
    private val ghost = sprite(
        ".....XXXX.....", "...XXXXXXXX...", "..XXXXXXXXXX..", ".XXwwXXXXwwXX.", ".XwwwwXXwwwwX.",
        ".XwwBBXXwwBBX.", "XXwwBBXXwwBBXX", "XXXwwXXXXwwXXX", "XXXXXXXXXXXXXX", "XXXXXXXXXXXXXX",
        "XXXXXXXXXXXXXX", "XXXXXXXXXXXXXX", "XX.XXX..XXX.XX", "X...XX..XX...X",
    )

    /** Четыре призрака разных цветов, глаза смотрят вправо. */
    val ghosts = listOf(0xFFFF0000, 0xFFFFB8FF, 0xFF00FFFF, 0xFFFFB852).map { ghost.recolor(mapOf('X' to it.toInt())) }

    /** Испуганный призрак. */
    val ghostScared = ghost.recolor(mapOf('X' to 0xFF2121FF.toInt(), 'w' to 0xFF2121FF.toInt(), 'B' to 0xFFFFB8FF.toInt()))

    val invaderA = sprite(
        "..c.....c..", "...c...c...", "..ccccccc..", ".cc.ccc.cc.", "ccccccccccc", "c.ccccccc.c", "c.c.....c.c", "...cc.cc...",
    )
    val invaderB = sprite(
        "..c.....c..", "c..c...c..c", "c.ccccccc.c", "ccc.ccc.ccc", "ccccccccccc", ".ccccccccc.", "..c.....c..", ".c.......c.",
    )

    /** Вспышка взрыва. */
    val explosion = sprite(
        "....w...w....", ".w...w.w...w.", "..w.......w..", "...w.....w...", "ww.........ww",
        "...w.....w...", "..w.......w..", ".w...w.w...w.", "....w...w....",
    )
    val cannon = sprite("......G......", ".....GGG.....", ".....GGG.....", ".GGGGGGGGGGG.", "GGGGGGGGGGGGG", "GGGGGGGGGGGGG", "GGGGGGGGGGGGG")
    val saucer = sprite(
        ".....rrrrrr.....", "...rrcccccccr...", "..rrrrrrrrrrrr..", ".rr.yy.yy.yy.rr.", "rrrrrrrrrrrrrrrr", "..rrr..rr..rrr..", "...r........r...",
    )
    val cow = sprite("ww........ww", ".wwwwwwwwww.", ".wkwwwkwwww.", "wwwwwkkwwwww", "wwwwwwwwwwkw", ".wwwwwwwwww.", ".w.w....w.w.", ".w.w....w.w.")

    /** Утка: крылья вверх / вниз (смотрит вправо). */
    val duckWingsUp = sprite(
        "..nn.........", "..nnn........", "...nnn...GG..", "...nnnn.GGGo.", "....nnnnWGG..",
        "..nnnnnnnn...", ".nnnnnnnnn...", "nnnnnnnnn....", ".nnnnnnn.....", "...oo........",
    )
    val duckWingsDown = sprite(
        ".........GG..", "........GGGo.", ".......WGG...", "..nnnnnnnn...", ".nnnnnnnnn...",
        "nnnnnnnnn....", ".nnnnnnnn....", "..nnnn.......", "..nnn........", "...oo........",
    )

    /** Стрелка вверх и буквы кнопок B и A. */
    val arrow = sprite("...W...", "..WWW..", ".WWWWW.", "WWWWWWW", "..WWW..", "..WWW..", "..WWW..")
    val letterB = sprite("WWWW.", "W...W", "W...W", "WWWW.", "W...W", "W...W", "WWWW.")
    val letterA = sprite(".WWW.", "W...W", "W...W", "WWWWW", "W...W", "W...W", "W...W")

    /** Спорткар, вид сзади. */
    val carRear = sprite(
        "......rrrrrrrrrrrr......", ".....rcccccccccccccr....", "....rrcccccccccccccrr...", "...rrrrrrrrrrrrrrrrrrr..",
        "..rrrrrrrrrrrrrrrrrrrrr.", ".rRRrrrrrrrrrrrrrrrrRRr.", ".rRRrrrrWWWWWWWWrrrrRRr.", ".rrrrrrrrrrrrrrrrrrrrrr.",
        ".rrrrrrrrrrrrrrrrrrrrrr.", ".kkk................kkk.", ".kkk................kkk.",
    )
    val heart = sprite(".RR...RR.", "RWRR.RRRR", "RRRRRRRRR", "RRRRRRRRR", ".RRRRRRR.", "..RRRRR..", "...RRR...", "....R....")
    val heartEmpty = sprite(".dd...dd.", "d..d.d..d", "d...d...d", "d.......d", ".d.....d.", "..d...d..", "...d.d...", "....d....")
    val floppy = sprite(
        "bbbbbbbbbbbbb.", "bbggggggggbbbb", "bbggggkkggbbbb", "bbggggkkggbbbb", "bbggggggggbbbb", "bbbbbbbbbbbbbb", "bbWWWWWWWWWWbb",
        "bbWWWWWWWWWWbb", "bbWddddddddWbb", "bbWWWWWWWWWWbb", "bbWddddddWWWbb", "bbWWWWWWWWWWbb", "bbWWWWWWWWWWbb", "bbbbbbbbbbbbbb",
    )

    /** Истребитель игрока (носом вверх). */
    val fighter = sprite(
        "......W......", "......W......", ".....WWW.....", ".....WWW.....", "..r..WWW..r..", "..r.WWWWW.r..",
        "..WWWWbWWWW..", "r.WWWbbbWWW.r", "rWWWWWbWWWWWr", "rWW.WWWWW.WWr", "rW..r.W.r..Wr", "r...r...r...r",
    )

    /** Пришелец-пчела. */
    val bee = sprite("..b.....b..", ".bbb...bbb.", "..byyyyyb..", "..yryyyry..", "...yyyyy...", "..y.yyy.y..", ".y..y.y..y.", "....y.y....")

    /** Пришелец-бабочка. */
    val butterfly = sprite(
        "...r...r...", "....r.r....", ".bb.rrr.bb.", "bbbrrWrrbbb", "bbrrrWrrrbb",
        ".brrWWWrrb.", "..rrrWrrr..", ".rr.rrr.rr.", "rr...r...rr",
    )

    /** Флагман пришельцев: целый и после первого попадания. */
    val flagship = sprite(
        "....y...y....", ".....y.y.....", "...GGGGGGG...", "..GGyGGGyGG..", "..GGGGGGGGG..", "mm.GGGGGGG.mm",
        "mmm.GGGGG.mmm", "mmmm.GGG.mmmm", "mmm..G.G..mmm", "mm..G...G..mm",
    )
    val flagshipDamaged = flagship.recolor(mapOf('G' to 0xFF3D5AFE.toInt(), 'm' to 0xFFFF9EC4.toInt()))

    val chestClosed = sprite(
        "..nnnnnnnnnnnn..", ".nnyynnnnnnyynn.", "nnnyynnnnnnyynnn", "yyyyyyyyyyyyyyyy", "nnnyynnyynnyynnn",
        "nnnyynnkknnyynnn", "nnnyynnyynnyynnn", "nnnyynnnnnnyynnn", "nnnyynnnnnnyynnn", "yyyyyyyyyyyyyyyy",
    )
    val chestOpen = sprite(
        ".nnnnnnnnnnnnnn.", "nnyynnnnnnnnyynn", "yyyyyyyyyyyyyyyy", "................", "WWWWWWWWWWWWWWWW", "nnnyynnyynnyynnn",
        "nnnyynnkknnyynnn", "nnnyynnyynnyynnn", "nnnyynnnnnnyynnn", "nnnyynnnnnnyynnn", "yyyyyyyyyyyyyyyy",
    )
}
