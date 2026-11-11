package com.github.gezimos.inkos.debug

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.gezimos.inkos.ui.compose.MoveHandleGlyph

class HandleGalleryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = ComposeView(this)
        view.setContent {
            Surface(color = Color.White) {
                Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                    Text("Move handle styles", fontSize = 30.sp, color = Color.Black)
                    Spacer(Modifier.height(10.dp))
                    val styles = listOf(
                        0 to "A 原样式",
                        1 to "B 实心箭头",
                        2 to "C 十字准星",
                        3 to "D 四角框",
                        4 to "E 四向握把",
                        5 to "F 圆环十字"
                    )
                    styles.chunked(2).forEach { row ->
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            row.forEach { (style, label) ->
                                Column(
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    MoveHandleGlyph(
                                        style = style,
                                        color = Color.Black.copy(alpha = 0.42f),
                                        modifier = Modifier.size(96.dp)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(label, fontSize = 22.sp, color = Color.Black)
                                }
                            }
                        }
                    }
                }
            }
        }
        setContentView(view)
    }
}
