package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.data.model.TvAppEntity

/** Hand-drawn 16×16 brand marks. Integer physical pixels keep every edge crisp. */
private data class PixelArtwork(val rows: List<String>, val palette: Map<Char, Color>)

private val customAccent = Color.Unspecified

private val netflixArtwork = PixelArtwork(
    rows = listOf(
        "................",
        "................",
        "...RRR....rrr...",
        "...RRR....rrr...",
        "...rRRR...rrr...",
        "...rRRR...rrr...",
        "...rrRRR..rrr...",
        "...rrrRRR.rrr...",
        "...rrrRRR.rrr...",
        "...rrr.RRRrrr...",
        "...rrr..RRRrr...",
        "...rrr..RRRrr...",
        "...rrr...RRRr...",
        "...rrr....RRR...",
        "................",
        "................",
    ),
    palette = mapOf('r' to Color(0xFFB20710), 'R' to Color(0xFFE50914))
)

private val youtubeArtwork = PixelArtwork(
    rows = listOf(
        "................",
        "................",
        "................",
        "..rrrrrrrrrrrr..",
        ".rrrrrrrrrrrrrr.",
        ".rrrrrwrrrrrrrr.",
        ".rrrrrwwwrrrrrr.",
        ".rrrrrwwwwwrrrr.",
        ".rrrrrwwwwwrrrr.",
        ".rrrrrwwwrrrrrr.",
        ".rrrrrwrrrrrrrr.",
        ".rrrrrrrrrrrrrr.",
        "..rrrrrrrrrrrr..",
        "................",
        "................",
        "................",
    ),
    palette = mapOf('r' to Color(0xFFFF0033), 'w' to Color(0xFFFFFFFF))
)

private val primeArtwork = PixelArtwork(
    rows = listOf(
        "................",
        "................",
        ".....bb.........",
        ".....bbbb.......",
        ".....bbbbb......",
        ".....bbbbbb.....",
        ".....bbbbb......",
        ".....bbbb.......",
        ".....bb.........",
        "............bbb.",
        "..b..........bb.",
        "...b........b.b.",
        "....bbbbbbbb....",
        "................",
        "................",
        "................",
    ),
    palette = mapOf('b' to Color(0xFF35C4FF))
)

private val disneyArtwork = PixelArtwork(
    rows = listOf(
        "................",
        ".......bbb......",
        ".....bb...bb....",
        "....b.......b...",
        "...b............",
        "..b.............",
        "....wwwww....b..",
        "....ww...ww.bbbb",
        "....ww...ww..b..",
        "....ww...ww.....",
        "....ww...ww.....",
        "....ww...ww.....",
        "....ww...ww.....",
        "....wwwww.......",
        "................",
        "................",
    ),
    palette = mapOf('b' to Color(0xFF60DFFF), 'w' to Color(0xFFF0FCFF))
)

private val spotifyArtwork = PixelArtwork(
    rows = listOf(
        "................",
        "....gggggggg....",
        "..gggggggggggg..",
        "..gggggggggggg..",
        ".gggggggggggggg.",
        ".gggkkkkkkkkkgg.",
        ".ggkkgggggggkkg.",
        ".gggggggggggggg.",
        ".gggkkkkkkkkggg.",
        ".ggkkggggggkkgg.",
        ".gggggggggggggg.",
        ".gggkkkkkkggggg.",
        "..gkkggggkkggg..",
        "..gggggggggggg..",
        "....gggggggg....",
        "................",
    ),
    palette = mapOf('g' to Color(0xFF1ED760), 'k' to Color(0xFF08140D))
)

private val twitchArtwork = PixelArtwork(
    rows = listOf(
        "................",
        "..pppppppppppp..",
        "..pwwwwwwwwwwp..",
        "..pwwwwwwwwwwp..",
        "..pwwwppwwppwp..",
        "..pwwwppwwppwp..",
        "..pwwwppwwppwp..",
        "..pwwwppwwppwp..",
        "..pwwwwwwwwwwp..",
        "..pwwwwwwwwwwp..",
        "..pppppppppppp..",
        "..pppppppp......",
        "..pppppppp......",
        "....ppp.........",
        "....ppp.........",
        "................",
    ),
    palette = mapOf('p' to Color(0xFFA970FF), 'w' to Color(0xFFFFFFFF))
)

private val plexArtwork = PixelArtwork(
    rows = listOf(
        "................",
        "................",
        ".....yyyy.......",
        "......yyyy......",
        ".......yyyy.....",
        "........yyyy....",
        ".........yyyy...",
        "..........yyyy..",
        "..........yyyy..",
        ".........yyyy...",
        "........yyyy....",
        ".......yyyy.....",
        "......yyyy......",
        ".....yyyy.......",
        "................",
        "................",
    ),
    palette = mapOf('y' to Color(0xFFEBAF00))
)

private val appleArtwork = PixelArtwork(
    rows = listOf(
        "................",
        "................",
        "................",
        ".....ww.........",
        ".....ww....w....",
        "...........w....",
        "..wwwww..wwwww..",
        ".wwwww.....w....",
        ".wwwww.....w.w.w",
        "..wwwww....w.w.w",
        "..wwwww....w.w.w",
        "...www.....w..w.",
        "................",
        "................",
        "................",
        "................",
    ),
    palette = mapOf('w' to Color(0xFFFFFFFF))
)

private val kodiArtwork = PixelArtwork(
    rows = listOf(
        "................",
        ".......bb.......",
        "......bbbb......",
        ".....bbbbbb.....",
        "....bbbbbbwb....",
        "...bbwwbbwbbb...",
        "..bbbwwbwbbbbb..",
        ".bbbbwwbbbbbbbb.",
        ".bbbbwwbbbbbbbb.",
        "..bbbwwbwbbbbb..",
        "...bbwwbbwbbb...",
        "....bbbbbbwb....",
        ".....bbbbbb.....",
        "......bbbb......",
        ".......bb.......",
        "................",
    ),
    palette = mapOf('b' to Color(0xFF29B6E8), 'w' to Color(0xFFE4F8FF))
)

private val vlcArtwork = PixelArtwork(
    rows = listOf(
        "................",
        "................",
        ".......oo.......",
        ".......oo.......",
        ".......oo.......",
        "......wwww......",
        "......wwww......",
        "......oooo......",
        ".....oooooo.....",
        ".....wwwwww.....",
        ".....wwwwww.....",
        "....oooooooo....",
        "....oooooooo....",
        "..oooooooooooo..",
        "..oooooooooooo..",
        "................",
    ),
    palette = mapOf('o' to Color(0xFFFF8C19), 'w' to Color(0xFFFFFFFF))
)

private val browserArtwork = PixelArtwork(
    rows = listOf(
        "................",
        "....rrrrrrrr....",
        "..rrrrrrrrrrrr..",
        "..rrrrrrrrrrrr..",
        ".rrrrrrrrrrrrrr.",
        ".rrrrwwwwwwrrrr.",
        ".ggggwbbbbwyyyy.",
        ".ggggwbbbbwyyyy.",
        ".ggggwbbbbwyyyy.",
        ".ggggwbbbbwyyyy.",
        ".ggggwwwwwwyyyy.",
        ".gggggggyyyyyyy.",
        "..ggggggyyyyyy..",
        "..ggggggyyyyyy..",
        "....ggggyyyy....",
        "................",
    ),
    palette = mapOf('r' to Color(0xFFEA4335), 'y' to Color(0xFFFBBC05), 'g' to Color(0xFF34A853), 'b' to Color(0xFF4285F4), 'w' to Color(0xFFFFFFFF))
)

private val videoArtwork = PixelArtwork(
    rows = listOf(
        "................",
        "................",
        "....a...........",
        "....aaa.........",
        "....aaaa........",
        "....aaaaaa......",
        "....aaaaaaa.....",
        "....aaaaaaaa....",
        "....aaaaaaaa....",
        "....aaaaaaa.....",
        "....aaaaaa......",
        "....aaaa........",
        "....aaa.........",
        "....a...........",
        "................",
        "................",
    ),
    palette = mapOf('a' to customAccent)
)

private val musicArtwork = PixelArtwork(
    rows = listOf(
        "................",
        "................",
        "................",
        "......aaaaaaa...",
        "......aaaaaaa...",
        "......aa...aa...",
        "......aa...aa...",
        "......aa...aa...",
        "......aa...aa...",
        "......aa...aa...",
        "......aa.aaaa...",
        "...aaaaa.aaaa...",
        "...aaaaa.aaaa...",
        "...aaaaa........",
        "................",
        "................",
    ),
    palette = mapOf('a' to customAccent)
)

private val gameArtwork = PixelArtwork(
    rows = listOf(
        "................",
        "................",
        "................",
        "................",
        "..aaaaaaaaaaaa..",
        "..aa.aaaaaaaaa..",
        "..a....aaa.aaa..",
        ".aaa.aaaaaaaaaa.",
        ".aaaaaaaaaaa.aa.",
        ".aaaaaaaaaaaaaa.",
        ".aaaaaaaaaaaaaa.",
        ".aa..........aa.",
        ".aa..........aa.",
        "................",
        "................",
        "................",
    ),
    palette = mapOf('a' to customAccent)
)

private val tvArtwork = PixelArtwork(
    rows = listOf(
        "................",
        "................",
        "................",
        ".aaaaaaaaaaaaaa.",
        ".aaaaaaaaaaaaaa.",
        ".aa..........aa.",
        ".aa..........aa.",
        ".aa..........aa.",
        ".aa..........aa.",
        ".aa..........aa.",
        ".aaaaaaaaaaaaaa.",
        ".aaaaaaaaaaaaaa.",
        ".......aa.......",
        ".......aa.......",
        "....aaaaaaaa....",
        "................",
    ),
    palette = mapOf('a' to customAccent)
)

private val rocketArtwork = PixelArtwork(
    rows = listOf(
        "................",
        ".......aa.......",
        ".......aa.......",
        ".....aaaaaa.....",
        ".....aaaaaa.....",
        ".....aa..aa.....",
        ".....aa..aa.....",
        ".....aaaaaa.....",
        "...aaaaaaaaaa...",
        "...aaaaaaaaaa...",
        "...aaa....aaa...",
        "...aaa....aaa...",
        "................",
        ".......aa.......",
        ".......aa.......",
        ".......aa.......",
    ),
    palette = mapOf('a' to customAccent)
)

private val flowArtwork = PixelArtwork(
    rows = listOf(
        "................",
        "................",
        "................",
        "................",
        ".tt.t...........",
        ".t..t...........",
        "ttt.t.ttt.t.t.t.",
        ".t..t.t.t.t.t.t.",
        ".t..t.t.t.t.t.t.",
        ".t..t.ttt..ttt..",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
    ),
    palette = mapOf('t' to Color(0xFF00E6C3))
)

private val maxArtwork = PixelArtwork(
    rows = listOf(
        "bbbbbbbbbbbbbbbb",
        "bbbbbbbbbbbbbbbb",
        "bbwbwbwwbbwwwbbb",
        "bbwbwbwbwbwbwbbb",
        "bbwwwbwwbbwbwbbb",
        "bbwbwbwwwbwwwbbb",
        "bbbbbbbbbbbbbbbb",
        "bbbbbbbbbbbbbbbb",
        "wwbwwbbwwbbwbbbw",
        "wbwbwbbbbwbbwbwb",
        "wbwbwbbwwwbbbwbb",
        "wbwbwbwbbwbbwbwb",
        "wbwbwbbwwwbwbbbw",
        "bbbbbbbbbbbbbbbb",
        "bbbbbbbbbbbbbbbb",
        "bbbbbbbbbbbbbbbb",
    ),
    palette = mapOf('b' to Color(0xFF153AEF), 'w' to Color(0xFFFFFFFF))
)

private val paramountArtwork = PixelArtwork(
    rows = listOf(
        "................",
        "....bbbbbbbb....",
        "..bbbbbwbbbbbb..",
        "..bbwbbbbbwbbb..",
        ".bbbbbbbbbbbbbb.",
        ".bbwbbbwbbbbwbb.",
        ".bbbbbwwwbbbbbb.",
        ".bbbbwwbwwbbbbb.",
        ".bbbwwbbbwwbbbb.",
        ".bbwwbbbbbwwbbb.",
        ".bbwwwwwwwwwwbb.",
        ".bbbbbbbbbbwwww.",
        "..bbbbbbbbbbwb..",
        "..bbbbbbbbbbbb..",
        "....bbbbbbbb....",
        "................",
    ),
    palette = mapOf('b' to Color(0xFF0064FF), 'w' to Color(0xFFFFFFFF))
)

@Composable
fun PixelAppIcon(app: TvAppEntity, modifier: Modifier = Modifier) {
    val identity = "${app.appName} ${app.appIdentifier}".lowercase(java.util.Locale.ROOT)
    val artwork = when {
        app.iconKey == "flow" || "ar.com.flow.androidtv" in identity -> flowArtwork
        app.iconKey == "max" || "com.wbd.stream" in identity || "hbo" in identity || app.appName.equals("Max", true) -> maxArtwork
        app.iconKey == "paramount" || "paramount" in identity || "com.cbs." in identity -> paramountArtwork
        "netflix" in identity -> netflixArtwork
        "youtube" in identity -> youtubeArtwork
        "prime" in identity || "amazonvideo" in identity -> primeArtwork
        "disney" in identity -> disneyArtwork
        "spotify" in identity -> spotifyArtwork
        "twitch" in identity -> twitchArtwork
        "plex" in identity -> plexArtwork
        "apple" in identity -> appleArtwork
        "kodi" in identity || "xbmc" in identity -> kodiArtwork
        "vlc" in identity || "videolan" in identity -> vlcArtwork
        "browser" in identity || "chrome" in identity || app.iconKey == "browser" -> browserArtwork
        app.iconKey == "music" -> musicArtwork
        app.iconKey == "game" -> gameArtwork
        app.iconKey == "tv" -> tvArtwork
        app.iconKey == "rocket" -> rocketArtwork
        else -> videoArtwork
    }
    Canvas(modifier.size(40.dp).semantics { contentDescription = app.appName }) {
        val pixel = (size.minDimension / 16f).toInt().coerceAtLeast(1).toFloat()
        val left = ((size.width - pixel * 16f) / 2f).toInt().toFloat()
        val top = ((size.height - pixel * 16f) / 2f).toInt().toFloat()
        artwork.rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, token ->
                artwork.palette[token]?.let { color ->
                    drawRect(
                        color = if (color == customAccent) Color(app.colorHex).copy(alpha = 1f) else color,
                        topLeft = Offset(left + x * pixel, top + y * pixel),
                        size = Size(pixel, pixel)
                    )
                }
            }
        }
    }
}
