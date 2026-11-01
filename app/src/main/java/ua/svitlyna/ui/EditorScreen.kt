package ua.svitlyna.ui

import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import ua.svitlyna.R
import ua.svitlyna.edit.EditorCanvasView
import ua.svitlyna.edit.EditorRender
import ua.svitlyna.edit.EditorTool
import ua.svitlyna.edit.Edits
import ua.svitlyna.edit.ShapeType
import ua.svitlyna.media.MediaItem
import ua.svitlyna.media.SaveCopy

private enum class Category { TRANSFORM, ADJUST, MARKUP, PRIVACY }

private val PALETTE = listOf(
    Color.White, Color.Black, Color(0xFFE53935), Color(0xFFFDD835), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA),
)

private val ASPECTS = listOf<Pair<Int, Float?>>(
    R.string.aspect_free to null,
    R.string.aspect_square to 1f,
    R.string.aspect_4_3 to 4f / 3f,
    R.string.aspect_3_4 to 3f / 4f,
    R.string.aspect_16_9 to 16f / 9f,
    R.string.aspect_9_16 to 9f / 16f,
)

/**
 * The photo editor. Its tools come from Iris Gallery's editor (cut, turn, straighten, colours, brush,
 * shapes with arrows, text, blur and pixels); Svitlyna adds solid blacking-out for privacy and saves
 * through its own copy rules: a NEW file next to the original, with the original's date taken and all
 * its metadata. The original is never changed.
 */
@Composable
fun EditorScreen(item: MediaItem, onClose: () -> Unit, onSaved: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val canvas = remember { EditorCanvasView(context) }
    var edits by remember { mutableStateOf(Edits()) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var category by remember { mutableStateOf(Category.TRANSFORM) }
    var tool by remember { mutableStateOf(EditorTool.CROP) }
    var color by remember { mutableStateOf(Color(0xFFE53935)) }
    var size by remember { mutableFloatStateOf(0.03f) }
    var shape by remember { mutableStateOf(ShapeType.ARROW) }
    var filled by remember { mutableStateOf(false) }
    var aspect by remember { mutableStateOf<Float?>(null) }
    var drawn by remember { mutableStateOf(false) }
    var undo by remember { mutableStateOf(false) }
    var redo by remember { mutableStateOf(false) }
    var askText by remember { mutableStateOf(false) }
    var textSelected by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var askLeave by remember { mutableStateOf(false) }

    fun changed() = drawn || edits != Edits() || canvas.session.crop.width() < 0.999f || canvas.session.crop.height() < 0.999f

    BackHandler { if (changed()) askLeave = true else onClose() }

    // A new preview when the photo is turned, flipped or straightened: these are baked into the
    // picture that is drawn on, exactly as in the saved copy. They are possible only before drawing.
    LaunchedEffect(edits.rotation, edits.flipH, edits.flipV, edits.straighten) {
        val p = EditorRender.preview(context, item, edits, 2048)
        if (p != null) {
            canvas.clearBitmaps()
            canvas.session.crop.set(0f, 0f, 1f, 1f)
            canvas.straightenAngle = 0f
            canvas.isStraightening = false
            canvas.setSource(p)
            preview = p
            canvas.setCropAspect(aspect)
        }
    }
    // While the slider moves, the canvas only turns the picture on the screen; letting go bakes it in.
    var straightening by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        canvas.onHistoryChanged = { u, r ->
            undo = u
            redo = r
            drawn = u || canvas.session.textOverlays.isNotEmpty()
        }
        canvas.onTextSelected = { textSelected = it != null }
    }

    // Keep the canvas in step with the chosen tool and settings.
    canvas.tool = tool
    canvas.brushColor = color.toArgb()
    canvas.brushRadius = size
    canvas.currentShapeType = shape
    canvas.shapeFilled = filled
    canvas.effectStrength = 24
    canvas.straightenAngle = straightening - edits.straighten
    canvas.colorFilter = if (edits.adjusted) ColorMatrixColorFilter(edits.colorMatrix()) else null

    fun pick(c: Category) {
        category = c
        tool = when (c) {
            Category.TRANSFORM -> EditorTool.CROP
            Category.ADJUST -> EditorTool.ADJUST
            Category.MARKUP -> EditorTool.DRAW
            Category.PRIVACY -> EditorTool.DRAW
        }
        if (c == Category.PRIVACY) {
            color = Color.Black
            size = 0.05f
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (changed()) askLeave = true else onClose() }) {
                Icon(Icons.Filled.Close, stringResource(R.string.close), tint = Color.White)
            }
            TextButton(enabled = undo, onClick = { canvas.undoStroke() }) { Text(stringResource(R.string.undo), color = if (undo) Color.White else Color.Gray) }
            TextButton(enabled = redo, onClick = { canvas.redoStroke() }) { Text(stringResource(R.string.redo), color = if (redo) Color.White else Color.Gray) }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Button(
                enabled = preview != null && !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        // A snapshot of the drawing, so the finger can't change it while saving.
                        val session = ua.svitlyna.edit.EditorSession().also { s ->
                            s.crop.set(canvas.session.crop)
                            s.strokes += canvas.session.strokes.map { it.copy(points = it.points.toMutableList()) }
                            s.textOverlays += canvas.session.textOverlays.map { it.copy() }
                        }
                        val uri = try {
                            val picture = EditorRender.render(context, item, edits, session, previewWidth = preview?.width ?: 2048)
                            picture?.let { SaveCopy.save(context, item, it, "edited").also { _ -> it.recycle() } }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            // Not enough memory for a very large photo, or an unusual file.
                            null
                        }
                        saving = false
                        if (uri != null) onSaved(uri) else failed = true
                    }
                },
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.save_copy))
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { canvas }, modifier = Modifier.fillMaxSize())
            if (preview == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .navigationBarsPadding()
                .padding(bottom = 4.dp),
        ) {
            when (category) {
                Category.TRANSFORM -> TransformTools(
                    aspect = aspect,
                    onAspect = { aspect = it; canvas.setCropAspect(it) },
                    canTurn = !drawn,
                    onRotate = { d -> edits = edits.copy(rotation = (edits.rotation + d + 360) % 360) },
                    onFlipH = { edits = edits.copy(flipH = !edits.flipH) },
                    onFlipV = { edits = edits.copy(flipV = !edits.flipV) },
                    straighten = straightening,
                    onStraighten = { straightening = it },
                    onStraightenDone = { edits = edits.copy(straighten = straightening) },
                    maxSide = edits.maxSide,
                    onMaxSide = { edits = edits.copy(maxSide = it) },
                    photoSide = maxOf(item.width, item.height),
                )
                Category.ADJUST -> AdjustTools(edits) { edits = it }
                Category.MARKUP -> MarkupTools(
                    tool = tool,
                    onTool = { tool = it },
                    shape = shape,
                    onShape = { shape = it },
                    filled = filled,
                    onFilled = { filled = it },
                    color = color,
                    onColor = { color = it },
                    size = size,
                    onSize = { size = it },
                    onAddText = { askText = true },
                    textSelected = textSelected,
                    onRemoveText = {
                        canvas.removeSelectedText()
                        drawn = canvas.canUndo || canvas.session.textOverlays.isNotEmpty()
                    },
                )
                Category.PRIVACY -> PrivacyTools(
                    tool = tool,
                    shape = shape,
                    size = size,
                    onSolid = { tool = EditorTool.DRAW; color = Color.Black },
                    onSolidBox = { tool = EditorTool.SHAPE; shape = ShapeType.RECTANGLE; filled = true; color = Color.Black },
                    onBlur = { tool = EditorTool.BLUR },
                    onPixels = { tool = EditorTool.PIXELATE },
                    onSize = { size = it },
                )
            }
            TabRow(selectedTabIndex = category.ordinal) {
                Category.entries.forEach { c ->
                    Tab(
                        selected = category == c,
                        onClick = { pick(c) },
                        text = {
                            Text(
                                stringResource(
                                    when (c) {
                                        Category.TRANSFORM -> R.string.edit_transform
                                        Category.ADJUST -> R.string.edit_adjust
                                        Category.MARKUP -> R.string.edit_markup
                                        Category.PRIVACY -> R.string.edit_privacy
                                    },
                                ),
                                maxLines = 1,
                            )
                        },
                    )
                }
            }
        }
    }

    if (askText) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askText = false },
            title = { Text(stringResource(R.string.edit_add_text)) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = false) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    canvas.addTextOverlay(text.trim(), color.toArgb(), android.graphics.Color.argb(160, 0, 0, 0), 0.06f)
                    tool = EditorTool.TEXT
                    drawn = true
                    askText = false
                }) { Text(stringResource(R.string.add)) }
            },
            dismissButton = { TextButton(onClick = { askText = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (askLeave) {
        AlertDialog(
            onDismissRequest = { askLeave = false },
            title = { Text(stringResource(R.string.edit_leave_title)) },
            text = { Text(stringResource(R.string.edit_leave)) },
            confirmButton = { TextButton(onClick = { askLeave = false; onClose() }) { Text(stringResource(R.string.edit_discard)) } },
            dismissButton = { TextButton(onClick = { askLeave = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (failed) {
        AlertDialog(
            onDismissRequest = { failed = false },
            text = { Text(stringResource(R.string.edit_save_failed)) },
            confirmButton = { TextButton(onClick = { failed = false }) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp).size(width = 96.dp, height = 20.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TransformTools(
    aspect: Float?,
    onAspect: (Float?) -> Unit,
    canTurn: Boolean,
    onRotate: (Int) -> Unit,
    onFlipH: () -> Unit,
    onFlipV: () -> Unit,
    straighten: Float,
    onStraighten: (Float) -> Unit,
    onStraightenDone: () -> Unit,
    maxSide: Int,
    onMaxSide: (Int) -> Unit,
    photoSide: Int,
) {
    ChipRow {
        ASPECTS.forEach { (label, value) ->
            FilterChip(selected = aspect == value, onClick = { onAspect(value) }, label = { Text(stringResource(label)) })
        }
    }
    ChipRow {
        TextButton(enabled = canTurn, onClick = { onRotate(-90) }) { Text(stringResource(R.string.edit_rotate_left)) }
        TextButton(enabled = canTurn, onClick = { onRotate(90) }) { Text(stringResource(R.string.edit_rotate_right)) }
        TextButton(enabled = canTurn, onClick = onFlipH) { Text(stringResource(R.string.edit_flip_h)) }
        TextButton(enabled = canTurn, onClick = onFlipV) { Text(stringResource(R.string.edit_flip_v)) }
    }
    if (!canTurn) {
        Text(stringResource(R.string.edit_turn_first), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
    }
    if (canTurn) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.edit_straighten), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp).size(width = 96.dp, height = 20.dp))
            Slider(
                value = straighten,
                onValueChange = onStraighten,
                onValueChangeFinished = onStraightenDone,
                valueRange = -45f..45f,
                modifier = Modifier.weight(1f),
            )
        }
    }
    // Size of the copy: the longer side in pixels (only smaller than the photo makes sense).
    ChipRow {
        Text(stringResource(R.string.edit_copy_size), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 4.dp))
        FilterChip(selected = maxSide == 0, onClick = { onMaxSide(0) }, label = { Text(stringResource(R.string.edit_size_original)) })
        SIZES.filter { photoSide <= 0 || it < photoSide }.forEach { px ->
            FilterChip(selected = maxSide == px, onClick = { onMaxSide(px) }, label = { Text("$px px") })
        }
    }
}

/** Longer side of the copy: for printing, for the screen, for messengers. */
private val SIZES = listOf(4096, 2048, 1600, 1080)

@Composable
private fun AdjustTools(edits: Edits, onChange: (Edits) -> Unit) {
    LabeledSlider(stringResource(R.string.edit_brightness), edits.brightness, -0.5f..0.5f) { onChange(edits.copy(brightness = it)) }
    LabeledSlider(stringResource(R.string.edit_contrast), edits.contrast, 0.5f..1.5f) { onChange(edits.copy(contrast = it)) }
    LabeledSlider(stringResource(R.string.edit_saturation), edits.saturation, 0f..2f) { onChange(edits.copy(saturation = it)) }
    LabeledSlider(stringResource(R.string.edit_warmth), edits.warmth, -1f..1f) { onChange(edits.copy(warmth = it)) }
    ChipRow {
        TextButton(onClick = { onChange(edits.copy(brightness = 0f, contrast = 1f, saturation = 1f, warmth = 0f)) }) {
            Text(stringResource(R.string.edit_reset))
        }
    }
}

@Composable
private fun MarkupTools(
    tool: EditorTool,
    onTool: (EditorTool) -> Unit,
    shape: ShapeType,
    onShape: (ShapeType) -> Unit,
    filled: Boolean,
    onFilled: (Boolean) -> Unit,
    color: Color,
    onColor: (Color) -> Unit,
    size: Float,
    onSize: (Float) -> Unit,
    onAddText: () -> Unit,
    textSelected: Boolean,
    onRemoveText: () -> Unit,
) {
    ChipRow {
        FilterChip(selected = tool == EditorTool.DRAW, onClick = { onTool(EditorTool.DRAW) }, label = { Text(stringResource(R.string.edit_brush)) })
        listOf(
            ShapeType.ARROW to R.string.edit_arrow,
            ShapeType.RECTANGLE to R.string.edit_rect,
            ShapeType.OVAL to R.string.edit_oval,
            ShapeType.LINE to R.string.edit_line,
        ).forEach { (s, label) ->
            FilterChip(
                selected = tool == EditorTool.SHAPE && shape == s,
                onClick = { onTool(EditorTool.SHAPE); onShape(s) },
                label = { Text(stringResource(label)) },
            )
        }
        FilterChip(selected = tool == EditorTool.TEXT, onClick = onAddText, label = { Text(stringResource(R.string.edit_text)) })
        if (tool == EditorTool.SHAPE && shape != ShapeType.ARROW && shape != ShapeType.LINE) {
            FilterChip(selected = filled, onClick = { onFilled(!filled) }, label = { Text(stringResource(R.string.edit_filled)) })
        }
        if (tool == EditorTool.TEXT && textSelected) {
            TextButton(onClick = onRemoveText) { Text(stringResource(R.string.edit_remove_text)) }
        }
    }
    ChipRow {
        PALETTE.forEach { c ->
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(c)
                    .border(if (c == color) 3.dp else 1.dp, if (c == color) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                    .clickable { onColor(c) },
            )
        }
    }
    LabeledSlider(stringResource(R.string.edit_size), size, 0.005f..0.08f, onSize)
}

@Composable
private fun PrivacyTools(
    tool: EditorTool,
    shape: ShapeType,
    size: Float,
    onSolid: () -> Unit,
    onSolidBox: () -> Unit,
    onBlur: () -> Unit,
    onPixels: () -> Unit,
    onSize: (Float) -> Unit,
) {
    ChipRow {
        FilterChip(selected = tool == EditorTool.DRAW, onClick = onSolid, label = { Text(stringResource(R.string.edit_black_brush)) })
        FilterChip(selected = tool == EditorTool.SHAPE && shape == ShapeType.RECTANGLE, onClick = onSolidBox, label = { Text(stringResource(R.string.edit_black_box)) })
        FilterChip(selected = tool == EditorTool.BLUR, onClick = onBlur, label = { Text(stringResource(R.string.edit_blur)) })
        FilterChip(selected = tool == EditorTool.PIXELATE, onClick = onPixels, label = { Text(stringResource(R.string.edit_pixels)) })
    }
    Text(
        stringResource(if (tool == EditorTool.BLUR || tool == EditorTool.PIXELATE) R.string.edit_blur_warning else R.string.edit_black_hint),
        style = MaterialTheme.typography.bodySmall,
        color = if (tool == EditorTool.BLUR || tool == EditorTool.PIXELATE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    LabeledSlider(stringResource(R.string.edit_size), size, 0.01f..0.12f, onSize)
}
