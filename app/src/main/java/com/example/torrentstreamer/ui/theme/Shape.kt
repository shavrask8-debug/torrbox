package com.example.torrentstreamer.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Shapes
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.rectangle
import androidx.graphics.shapes.toPath


class SquircleShape(
    private val cornerRadiusRatio: Float = 0.22f,
    private val smoothing: Float = 0.6f
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        if (size.width <= 1f || size.height <= 1f) {
            return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        }

        val roundedPolygon = RoundedPolygon.rectangle(
            width = size.width,
            height = size.height,
            rounding = CornerRounding(
                radius = size.minDimension * cornerRadiusRatio,
                smoothing = smoothing
            )
        )
        val path = roundedPolygon.toPath().asComposePath()
        val matrix = Matrix().apply {
            translate(size.width / 2f, size.height / 2f)
        }
        path.transform(matrix)
        return Outline.Generic(path)
    }
}

/**
 * Іменовані посилання на MaterialShapes для майбутнього анімованого морфінгу.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object AppShapes {
    val PlayIdle = MaterialShapes.Circle
    val PlayActive = MaterialShapes.Cookie9Sided
    val LoaderPolygons = listOf(MaterialShapes.SoftBurst, MaterialShapes.Cookie9Sided, MaterialShapes.Pentagon)
}

/**
 * Системні форми для карт та поверхонь (containment-принцип M3E).
 */
val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)