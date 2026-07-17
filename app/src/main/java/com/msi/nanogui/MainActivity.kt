package com.msi.nanogui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.view.LayoutInflater
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.msi.nanogui.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val engine = EditorEngine()

    private val currentUriState = mutableStateOf<Uri?>(null)
    private val currentFileNameState = mutableStateOf<String?>("Untitled")
    private val fileExtensionState = mutableStateOf("")
    private val isModifiedState = mutableStateOf(false)
    private val textValueState = mutableStateOf(TextFieldValue(""))

    private fun saveAutosaveDraft(text: String) {
        try {
            openFileOutput("autosave_draft.txt", Context.MODE_PRIVATE).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
            }
            val prefs = getSharedPreferences("nano_gui_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("draft_uri", currentUriState.value?.toString())
                .putString("draft_filename", currentFileNameState.value)
                .putBoolean("draft_is_modified", isModifiedState.value)
                .putBoolean("draft_exists", true)
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadAutosaveDraft(): String? {
        return try {
            openFileInput("autosave_draft.txt").use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun clearAutosave() {
        try {
            deleteFile("autosave_draft.txt")
            val prefs = getSharedPreferences("nano_gui_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .remove("draft_uri")
                .remove("draft_filename")
                .remove("draft_is_modified")
                .putBoolean("draft_exists", false)
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isModifiedState.value && textValueState.value.text.isNotEmpty()) {
            saveAutosaveDraft(textValueState.value.text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        engine.loadSyntaxColors(this)

        val intentUri = intent?.data
        if (intentUri != null) {
            handleIncomingUri(intentUri)
        }

        // Check first run and Termux setup
        val prefs = getSharedPreferences("nano_gui_prefs", Context.MODE_PRIVATE)
        val firstRun = prefs.getBoolean("first_run", true)
        val skipSetup = prefs.getBoolean("skip_setup", false)

        if (firstRun) {
            prefs.edit().putBoolean("first_run", false).apply()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
                android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 101)
            }
        }

        if (!skipSetup && isTermuxInstalled()) {
            showTermuxSetupDialog()
        }

        setContent {
            val isDark = isSystemInDarkTheme()
            val backgroundColor = if (isDark) Color.Black else Color.White
            val view = LocalView.current
            val context = LocalContext.current

            LaunchedEffect(isDark) {
                val activity = context as? Activity
                if (activity != null) {
                    val window = activity.window
                    val insetsController = WindowCompat.getInsetsController(window, view)
                    insetsController.isAppearanceLightStatusBars = !isDark
                    insetsController.isAppearanceLightNavigationBars = !isDark
                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                    window.navigationBarColor = android.graphics.Color.TRANSPARENT
                }
            }

            // Custom purely monochrome dark/light color schemes
            val customColorScheme = if (isDark) {
                darkColorScheme(
                    primary = Color.White,
                    onPrimary = Color.Black,
                    background = Color.Black,
                    onBackground = Color.White,
                    surface = Color.Black,
                    onSurface = Color.White
                )
            } else {
                lightColorScheme(
                    primary = Color.Black,
                    onPrimary = Color.White,
                    background = Color.White,
                    onBackground = Color.Black,
                    surface = Color.White,
                    onSurface = Color.Black
                )
            }

            MaterialTheme(colorScheme = customColorScheme) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing,
                    containerColor = backgroundColor
                ) { innerPadding ->
                    EditorScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val intentUri = intent.data
        if (intentUri != null) {
            handleIncomingUri(intentUri)
        }
    }

    private fun handleIncomingUri(uri: Uri) {
        currentUriState.value = uri
        currentFileNameState.value = getFileName(this, uri)
        val initialExt = getFileExtension(currentFileNameState.value ?: "")
        fileExtensionState.value = initialExt
        if (engine.loadFromUri(this, uri)) {
            val content = engine.textState.value
            textValueState.value = TextFieldValue(content)
            isModifiedState.value = false
            triggerBackgroundAnalysis(currentFileNameState.value ?: "", content)
            clearAutosave()
        } else {
            Toast.makeText(this, "Failed to load incoming file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var name = "Untitled"
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        name = it.getString(index)
                    }
                }
            }
        } else {
            name = uri.lastPathSegment ?: "Untitled"
        }
        return name
    }

    private fun getFileExtension(fileName: String): String {
        val dotIndex = fileName.lastIndexOf('.')
        return if (dotIndex != -1 && dotIndex < fileName.length - 1) {
            fileName.substring(dotIndex + 1).lowercase()
        } else {
            ""
        }
    }

    private fun triggerBackgroundAnalysis(filename: String, content: String) {
        lifecycleScope.launch {
            try {
                val result = GeminiAnalyzer.analyzeFile(filename, content)
                if (result != null) {
                    if (result.is_code) {
                        fileExtensionState.value = result.suggested_extension
                    } else {
                        fileExtensionState.value = "txt"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun isTermuxInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo("com.termux", 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun showTermuxSetupDialog() {
        try {
            val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_termux_setup, null)
            val textViewCommand = dialogView.findViewById<TextView>(R.id.setup_command)
            val buttonCopy = dialogView.findViewById<Button>(R.id.btn_copy)
            val buttonClose = dialogView.findViewById<Button>(R.id.btn_close)
            val checkBoxDontShow = dialogView.findViewById<CheckBox>(R.id.setup_checkbox)

            val base64Script = try {
                val resourceId = resources.getIdentifier("nano_wrapper", "raw", packageName)
                if (resourceId != 0) {
                    val stream = resources.openRawResource(resourceId)
                    val bytes = stream.use { it.readBytes() }
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                } else {
                    ""
                }
            } catch (e: Exception) {
                ""
            }

            val command = "[ ! -f \$PREFIX/bin/nano.orig ] && cp \$PREFIX/bin/nano \$PREFIX/bin/nano.orig; " +
                    "echo \"$base64Script\" | base64 -d > \$PREFIX/bin/nano && " +
                    "chmod +x \$PREFIX/bin/nano && echo \"Nano GUI installed\""

            textViewCommand.text = command

            val builder = android.app.AlertDialog.Builder(this)
            builder.setView(dialogView)
            val alertDialog = builder.create()
            alertDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            buttonCopy.setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Termux Setup Command", command)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Setup command copied!", Toast.LENGTH_SHORT).show()
            }

            buttonClose.setOnClickListener {
                val skip = checkBoxDontShow.isChecked
                getSharedPreferences("nano_gui_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("skip_setup", skip)
                    .apply()
                alertDialog.dismiss()
            }

            alertDialog.show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveFile(onSuccess: (() -> Unit)? = null) {
        val uri = currentUriState.value
        if (uri != null) {
            if (engine.saveToUri(this, uri, textValueState.value.text)) {
                isModifiedState.value = false
                Toast.makeText(this, "Saved successfully", Toast.LENGTH_SHORT).show()
                clearAutosave()
                onSuccess?.invoke()
            } else {
                Toast.makeText(this, "Failed to write, trying Save As...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Composable
    fun EditorScreen(modifier: Modifier = Modifier) {
        var textValue by textValueState
        var currentUri by currentUriState
        var currentFileName by currentFileNameState
        var fileExtension by fileExtensionState
        var isModified by isModifiedState

        val scrollState = rememberScrollState()
        val focusRequester = remember { FocusRequester() }
        var showSearchDialog by remember { mutableStateOf(false) }
        var showHelpDialog by remember { mutableStateOf(false) }
        var showRenameDialog by remember { mutableStateOf(false) }
        var showKeyboardShortcutsDialog by remember { mutableStateOf(false) }
        var showRestoreDraftDialog by remember { mutableStateOf(false) }
        var editorFontSize by remember { mutableStateOf(14f) }
        var baseFontSize by remember { mutableStateOf(14f) }

        val isDark = isSystemInDarkTheme()
        val backgroundColor = if (isDark) Color.Black else Color.White
        val textColor = if (isDark) Color.White else Color.Black
        val secondaryTextColor = if (isDark) Color.LightGray else Color.DarkGray
        val borderColor = if (isDark) Color(0xFF222222) else Color(0xFFDDDDDD)
        val surfaceColor = if (isDark) Color(0xFF0C0C0C) else Color(0xFFF9F9F9)

        // SAF Launchers
        val openDocumentLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
            onResult = { uri ->
                uri?.let {
                    currentUri = it
                    currentFileName = getFileName(this@MainActivity, it)
                    val initialExt = getFileExtension(currentFileName ?: "")
                    fileExtension = initialExt
                    if (engine.loadFromUri(this@MainActivity, it)) {
                        val content = engine.textState.value
                        textValue = TextFieldValue(content)
                        isModified = false
                        triggerBackgroundAnalysis(currentFileName ?: "", content)
                        clearAutosave()
                    } else {
                        Toast.makeText(this@MainActivity, "Failed to load file", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )

        val createDocumentLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/*"),
            onResult = { uri ->
                uri?.let {
                    currentUri = it
                    currentFileName = getFileName(this@MainActivity, it)
                    fileExtension = getFileExtension(currentFileName ?: "")
                    if (engine.saveToUri(this@MainActivity, it, textValue.text)) {
                        isModified = false
                        Toast.makeText(this@MainActivity, "Saved successfully", Toast.LENGTH_SHORT).show()
                        clearAutosave()
                    } else {
                        Toast.makeText(this@MainActivity, "Failed to save file", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )

        // Actions helper
        val onSaveAction = {
            if (currentUri != null) {
                saveFile()
            } else {
                createDocumentLauncher.launch(currentFileName ?: "Untitled")
            }
        }

        val onSearchAction = {
            showSearchDialog = true
        }

        val onExitAction = {
            finish()
        }

        val onCutAction = {
            val selection = textValue.selection
            val newText = engine.cutSelection(selection.start, selection.end)
            textValue = TextFieldValue(
                text = newText,
                selection = TextRange(selection.start.coerceAtMost(newText.length))
            )
            isModified = true
        }

        val onPasteAction = {
            val selection = textValue.selection
            val newText = engine.paste(selection.start)
            textValue = TextFieldValue(
                text = newText,
                selection = TextRange((selection.start + engine.cutBuffer.length).coerceAtMost(newText.length))
            )
            isModified = true
        }

        val onUndoAction = {
            val previous = engine.undo()
            if (previous != null) {
                textValue = TextFieldValue(
                    text = previous,
                    selection = TextRange(previous.length.coerceAtMost(textValue.selection.start))
                )
                isModified = true
            } else {
                Toast.makeText(this@MainActivity, "Nothing to undo", Toast.LENGTH_SHORT).show()
            }
        }

        val onRedoAction = {
            val next = engine.redo()
            if (next != null) {
                textValue = TextFieldValue(
                    text = next,
                    selection = TextRange(next.length.coerceAtMost(textValue.selection.start))
                )
                isModified = true
            } else {
                Toast.makeText(this@MainActivity, "Nothing to redo", Toast.LENGTH_SHORT).show()
            }
        }

        // Request keyboard focus and check for draft on startup
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            val prefs = getSharedPreferences("nano_gui_prefs", Context.MODE_PRIVATE)
            val draftExists = prefs.getBoolean("draft_exists", false)
            if (draftExists && intent?.data == null) {
                showRestoreDraftDialog = true
            }
        }

        // Auto-save LaunchedEffect
        LaunchedEffect(textValue.text, isModified) {
            if (isModified && textValue.text.isNotEmpty()) {
                kotlinx.coroutines.delay(1500)
                saveAutosaveDraft(textValue.text)
            }
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .background(backgroundColor)
        ) {
            // Top Status Bar (Immersive UI style)
            val text = textValue.text
            val wordCount = if (text.isBlank()) 0 else text.trim().split("\\s+".toRegex()).size

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .background(surfaceColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .clickable { showRenameDialog = true }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = currentFileName ?: "Untitled",
                            color = textColor,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "✎",
                            color = secondaryTextColor,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                    Text(
                        text = "W: $wordCount",
                        color = secondaryTextColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isModified) {
                        Text(
                            text = "[Modified]",
                            color = textColor,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    
                    Row(
                        modifier = Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                            .clickable { showKeyboardShortcutsDialog = true }
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                        val isPhysicalKeyboard = configuration.keyboard == android.content.res.Configuration.KEYBOARD_QWERTY ||
                                configuration.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO
                        
                        val keyboardLabel = if (isPhysicalKeyboard) "Physical key" else "Soft key"
                        
                        Text(
                            text = "⌨ $keyboardLabel",
                            color = secondaryTextColor,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }



            // Gutter + Text Field
            val density = LocalDensity.current
            val lineDp = (editorFontSize * (22f / 14f)).dp
            val lineHeightPx = with(density) { lineDp.toPx() }

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(backgroundColor)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            var initialDistance = 0f
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val changes = event.changes
                                val activePointers = changes.filter { it.pressed }
                                
                                if (activePointers.size >= 2) {
                                    // Consume events on Initial pass to block BasicTextField cursor movement or selection
                                    changes.forEach { it.consume() }
                                    
                                    val p1 = activePointers[0].position
                                    val p2 = activePointers[1].position
                                    val dx = p1.x - p2.x
                                    val dy = p1.y - p2.y
                                    val currentDistance = kotlin.math.sqrt(dx * dx + dy * dy)
                                    
                                    if (initialDistance == 0f) {
                                        initialDistance = currentDistance
                                        baseFontSize = editorFontSize
                                    } else if (initialDistance > 0f && currentDistance > 0f) {
                                        val scale = currentDistance / initialDistance
                                        val targetSize = baseFontSize * scale
                                        editorFontSize = targetSize.coerceIn(8f, 48f)
                                    }
                                } else {
                                    initialDistance = 0f
                                }
                            }
                        }
                    }
            ) {
                val totalLines = textValue.text.split("\n").size
                val scrollOffset = scrollState.value
                val firstVisibleIndex = (scrollOffset / lineHeightPx).toInt().coerceIn(0, totalLines - 1)
                
                // Buffer to keep render performance high
                val visibleLinesCount = 60
                val lastVisibleIndex = (firstVisibleIndex + visibleLinesCount).coerceAtMost(totalLines - 1)

                Row(modifier = Modifier.fillMaxSize()) {
                    // Line Number Gutter (Minimal and elegant)
                    Column(
                        modifier = Modifier
                            .widthIn(min = 30.dp)
                            .fillMaxHeight()
                            .background(surfaceColor)
                            .verticalScroll(scrollState)
                            .padding(end = 4.dp, top = 4.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Spacer(modifier = Modifier.height(lineDp * firstVisibleIndex))

                        for (i in (firstVisibleIndex + 1)..(lastVisibleIndex + 1)) {
                            Text(
                                text = "$i",
                                color = secondaryTextColor.copy(alpha = 0.6f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = (editorFontSize * 0.8f).coerceAtLeast(8f).sp,
                                textAlign = TextAlign.End,
                                modifier = Modifier.height(lineDp)
                            )
                        }

                        val remainingLines = totalLines - 1 - lastVisibleIndex
                        if (remainingLines > 0) {
                            Spacer(modifier = Modifier.height(lineDp * remainingLines))
                        }
                    }

                    // Divider line between Gutter and Editor Area
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(borderColor)
                    )

                    // BasicTextField Main Editing Area
                    BasicTextField(
                        value = textValue,
                        onValueChange = {
                            if (it.text != textValue.text) {
                                engine.pushUndo(textValue.text)
                                isModified = true
                            }
                            textValue = it
                            engine.textState.value = it.text
                        },
                        textStyle = TextStyle(
                            color = textColor,
                            fontFamily = FontFamily.Monospace,
                            fontSize = editorFontSize.sp,
                            lineHeight = (editorFontSize * (22f / 14f)).sp
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(scrollState)
                            .background(Color.Transparent)
                            .focusRequester(focusRequester)
                            .testTag("editor_text_field")
                            .onKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown && keyEvent.isCtrlPressed) {
                                    when (keyEvent.key) {
                                        Key.O, Key.S -> {
                                            onSaveAction()
                                            true
                                        }
                                        Key.W -> {
                                            onSearchAction()
                                            true
                                        }
                                        Key.X -> {
                                            onExitAction()
                                            true
                                        }
                                        Key.K -> {
                                            onCutAction()
                                            true
                                        }
                                        Key.U -> {
                                            onPasteAction()
                                            true
                                        }
                                        Key.Z -> {
                                            onUndoAction()
                                            true
                                        }
                                        Key.Y -> {
                                            onRedoAction()
                                            true
                                        }
                                        else -> false
                                    }
                                } else {
                                    false
                                }
                            }
                            .padding(start = 8.dp, top = 4.dp, end = 8.dp),
                        cursorBrush = SolidColor(textColor),
                        visualTransformation = { text ->
                            val highlighted = engine.buildHighlightedAnnotatedString(text.text, fileExtension)
                            TransformedText(highlighted, OffsetMapping.Identity)
                        }
                    )
                }
            }

            // Bottom Commands Bar (Immersive UI style)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .background(surfaceColor)
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    NanoShortcutButton(
                        shortcut = "^X",
                        label = "Exit",
                        onClick = onExitAction,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("exit_button")
                    )
                    NanoShortcutButton(
                        shortcut = "^O",
                        label = "Save",
                        onClick = onSaveAction,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("save_button")
                    )
                    NanoShortcutButton(
                        shortcut = "^W",
                        label = "Search",
                        onClick = onSearchAction,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("search_button")
                    )
                    NanoShortcutButton(
                        shortcut = "^K",
                        label = "Cut",
                        onClick = onCutAction,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cut_button")
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    NanoShortcutButton(
                        shortcut = "^U",
                        label = "Paste",
                        onClick = onPasteAction,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("paste_button")
                    )
                    NanoShortcutButton(
                        shortcut = "^Z",
                        label = "Undo",
                        onClick = onUndoAction,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("undo_button")
                    )
                    NanoShortcutButton(
                        shortcut = "^Y",
                        label = "Redo",
                        onClick = onRedoAction,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("redo_button")
                    )
                    NanoShortcutButton(
                        shortcut = "^R",
                        label = "Open",
                        onClick = { openDocumentLauncher.launch(arrayOf("text/*")) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Search & Replace Dialog
        if (showSearchDialog) {
            var searchQuery by remember { mutableStateOf("") }
            var replaceText by remember { mutableStateOf("") }
            var matches by remember { mutableStateOf(emptyList<IntRange>()) }
            var currentMatchIndex by remember { mutableStateOf(-1) }

            AlertDialog(
                onDismissRequest = { showSearchDialog = false },
                title = {
                    Text(
                        text = "Search & Replace",
                        color = textColor,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                },
                containerColor = surfaceColor,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                modifier = Modifier.border(width = 1.dp, color = borderColor, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
                text = {
                    Column {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                matches = engine.searchPositions(it)
                                currentMatchIndex = if (matches.isNotEmpty()) 0 else -1
                                if (matches.isNotEmpty()) {
                                    val match = matches[0]
                                    textValue = textValue.copy(
                                        selection = TextRange(match.first, match.last)
                                    )
                                }
                            },
                            label = { Text("Search text", color = secondaryTextColor, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                            textStyle = TextStyle(color = textColor, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = textColor,
                                unfocusedTextColor = textColor,
                                focusedBorderColor = textColor,
                                unfocusedBorderColor = secondaryTextColor,
                                focusedLabelColor = textColor,
                                unfocusedLabelColor = secondaryTextColor,
                                cursorColor = textColor
                            ),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        )

                        OutlinedTextField(
                            value = replaceText,
                            onValueChange = { replaceText = it },
                            label = { Text("Replace with", color = secondaryTextColor, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                            textStyle = TextStyle(color = textColor, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = textColor,
                                unfocusedTextColor = textColor,
                                focusedBorderColor = textColor,
                                unfocusedBorderColor = secondaryTextColor,
                                focusedLabelColor = textColor,
                                unfocusedLabelColor = secondaryTextColor,
                                cursorColor = textColor
                            ),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        )

                        if (matches.isNotEmpty()) {
                            Text(
                                text = "Match ${currentMatchIndex + 1} of ${matches.size}",
                                color = textColor,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        } else if (searchQuery.isNotEmpty()) {
                            Text(
                                text = "No matches found",
                                color = textColor,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            TextButton(
                                onClick = {
                                    if (matches.isNotEmpty()) {
                                        currentMatchIndex = if (currentMatchIndex <= 0) matches.size - 1 else currentMatchIndex - 1
                                        val match = matches[currentMatchIndex]
                                        textValue = textValue.copy(
                                            selection = TextRange(match.first, match.last)
                                        )
                                    }
                                },
                                enabled = matches.isNotEmpty(),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = textColor,
                                    disabledContentColor = secondaryTextColor
                                )
                            ) {
                                Text("Prev", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            }

                            TextButton(
                                onClick = {
                                    if (matches.isNotEmpty()) {
                                        currentMatchIndex = (currentMatchIndex + 1) % matches.size
                                        val match = matches[currentMatchIndex]
                                        textValue = textValue.copy(
                                            selection = TextRange(match.first, match.last)
                                        )
                                    }
                                },
                                enabled = matches.isNotEmpty(),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = textColor,
                                    disabledContentColor = secondaryTextColor
                                )
                            ) {
                                Text("Next", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            TextButton(
                                onClick = {
                                    val currentText = textValue.text
                                    val selection = textValue.selection
                                    if (selection.start != selection.end && searchQuery.isNotEmpty()) {
                                        engine.pushUndo(currentText)
                                        val newText = currentText.substring(0, selection.start) + replaceText + currentText.substring(selection.end)
                                        textValue = TextFieldValue(
                                            text = newText,
                                            selection = TextRange(selection.start + replaceText.length)
                                        )
                                        engine.textState.value = newText
                                        isModified = true
                                        matches = engine.searchPositions(searchQuery)
                                        if (matches.isNotEmpty()) {
                                            currentMatchIndex = currentMatchIndex.coerceIn(0, matches.size - 1)
                                            val match = matches[currentMatchIndex]
                                            textValue = textValue.copy(
                                                selection = TextRange(match.first, match.last)
                                            )
                                        } else {
                                            currentMatchIndex = -1
                                        }
                                    }
                                },
                                enabled = matches.isNotEmpty(),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = textColor,
                                    disabledContentColor = secondaryTextColor
                                )
                            ) {
                                Text("Replace", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            }

                            TextButton(
                                onClick = {
                                    val currentText = textValue.text
                                    if (searchQuery.isNotEmpty()) {
                                        engine.pushUndo(currentText)
                                        val newText = currentText.replace(searchQuery, replaceText, ignoreCase = true)
                                        textValue = TextFieldValue(newText)
                                        engine.textState.value = newText
                                        isModified = true
                                        matches = emptyList()
                                        currentMatchIndex = -1
                                        Toast.makeText(this@MainActivity, "Replaced all occurrences", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = searchQuery.isNotEmpty(),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = textColor,
                                    disabledContentColor = secondaryTextColor
                                )
                            ) {
                                Text("Replace All", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            }
                        }

                        TextButton(
                            onClick = { showSearchDialog = false },
                            modifier = Modifier.align(Alignment.End),
                            colors = ButtonDefaults.textButtonColors(contentColor = textColor)
                        ) {
                            Text("Close", fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            )
        }

        // Help / Nano Guide Dialog
        if (showHelpDialog) {
            AlertDialog(
                onDismissRequest = { showHelpDialog = false },
                containerColor = surfaceColor,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                modifier = Modifier.border(width = 1.dp, color = borderColor, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
                title = {
                    Text(
                        text = "GNU nano Help Guide",
                        color = textColor,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "This editor supports standard nano shortcuts. You can tap the on-screen buttons or use a hardware keyboard.",
                            color = secondaryTextColor,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        listOf(
                            "^G (Get Help)" to "Show this nano reference guide",
                            "^O (Write Out)" to "Save current file contents to storage",
                            "^R (Read File)" to "Open a document from system storage",
                            "^W (Where Is)" to "Search and replace text in file",
                            "^K (Cut)" to "Cut selection / clear current line",
                            "^U (Paste)" to "Paste text from the cut buffer",
                            "^Z (Undo)" to "Undo the last text operation",
                            "^Y (Redo)" to "Redo the previously undone operation",
                            "^X (Exit)" to "Close the editor application"
                        ).forEach { (cmd, desc) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = cmd.padEnd(15),
                                    color = textColor,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(110.dp)
                                )
                                Text(
                                    text = desc,
                                    color = secondaryTextColor,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                showHelpDialog = false
                                showTermuxSetupDialog()
                            },
                            modifier = Modifier.align(Alignment.Start),
                            colors = ButtonDefaults.textButtonColors(contentColor = textColor)
                        ) {
                            Text("Open Termux CLI Setup Command", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { showHelpDialog = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = textColor)
                    ) {
                        Text("Dismiss", fontFamily = FontFamily.Monospace)
                    }
                }
            )
        }

        // File Rename Dialog
        if (showRenameDialog) {
            var newFileName by remember { mutableStateOf(currentFileName ?: "Untitled") }

            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = {
                    Text(
                        text = "Rename",
                        color = textColor,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                containerColor = surfaceColor,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                modifier = Modifier.border(width = 1.dp, color = borderColor, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                text = {
                    Column {
                        OutlinedTextField(
                            value = newFileName,
                            onValueChange = { newFileName = it },
                            label = { Text("File Name", color = secondaryTextColor, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                            textStyle = TextStyle(color = textColor, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = textColor,
                                unfocusedTextColor = textColor,
                                focusedBorderColor = textColor,
                                unfocusedBorderColor = secondaryTextColor,
                                focusedLabelColor = textColor,
                                unfocusedLabelColor = secondaryTextColor,
                                cursorColor = textColor
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { showRenameDialog = false },
                            colors = ButtonDefaults.textButtonColors(contentColor = secondaryTextColor)
                        ) {
                            Text("Cancel", fontFamily = FontFamily.Monospace)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                val trimmed = newFileName.trim()
                                if (trimmed.isNotEmpty()) {
                                    currentFileName = trimmed
                                    val newExt = getFileExtension(trimmed)
                                    fileExtension = newExt
                                    // Trigger online analysis with Gemini or offline fallback
                                    triggerBackgroundAnalysis(trimmed, textValue.text)
                                }
                                showRenameDialog = false
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = textColor)
                        ) {
                            Text("Save", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            )
        }

        // Keyboard Shortcuts Dialog
        if (showKeyboardShortcutsDialog) {
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val isPhysicalKeyboard = configuration.keyboard == android.content.res.Configuration.KEYBOARD_QWERTY ||
                    configuration.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO

            AlertDialog(
                onDismissRequest = { showKeyboardShortcutsDialog = false },
                title = {
                    Text(
                        text = if (isPhysicalKeyboard) "Physical Keyboard Shortcuts" else "Soft Keyboard Guide",
                        color = textColor,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                containerColor = surfaceColor,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                modifier = Modifier.border(width = 1.dp, color = borderColor, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isPhysicalKeyboard) {
                            Text(
                                text = "Supported hardware shortcuts:",
                                color = secondaryTextColor,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            ShortcutRow(key = "Ctrl + X", desc = "Exit Editor", textColor = textColor, secondaryTextColor = secondaryTextColor)
                            ShortcutRow(key = "Ctrl + O", desc = "Save File", textColor = textColor, secondaryTextColor = secondaryTextColor)
                            ShortcutRow(key = "Ctrl + R", desc = "Open File", textColor = textColor, secondaryTextColor = secondaryTextColor)
                            ShortcutRow(key = "Ctrl + W", desc = "Search Text", textColor = textColor, secondaryTextColor = secondaryTextColor)
                            ShortcutRow(key = "Ctrl + K", desc = "Cut Line", textColor = textColor, secondaryTextColor = secondaryTextColor)
                            ShortcutRow(key = "Ctrl + U", desc = "Paste Text", textColor = textColor, secondaryTextColor = secondaryTextColor)
                            ShortcutRow(key = "Ctrl + Z", desc = "Undo Change", textColor = textColor, secondaryTextColor = secondaryTextColor)
                            ShortcutRow(key = "Ctrl + Y", desc = "Redo Change", textColor = textColor, secondaryTextColor = secondaryTextColor)
                        } else {
                            Text(
                                text = "Use the quick action buttons at the bottom of the editor to perform operations quickly:",
                                color = secondaryTextColor,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            SoftKeyRow(action = "Exit", desc = "Close editor", textColor = textColor, secondaryTextColor = secondaryTextColor)
                            SoftKeyRow(action = "Save", desc = "Save current file", textColor = textColor, secondaryTextColor = secondaryTextColor)
                            SoftKeyRow(action = "Search", desc = "Find & replace text", textColor = textColor, secondaryTextColor = secondaryTextColor)
                            SoftKeyRow(action = "Cut", desc = "Cut current line", textColor = textColor, secondaryTextColor = secondaryTextColor)
                            SoftKeyRow(action = "Paste", desc = "Insert clip text", textColor = textColor, secondaryTextColor = secondaryTextColor)
                            SoftKeyRow(action = "Undo", desc = "Revert last change", textColor = textColor, secondaryTextColor = secondaryTextColor)
                            SoftKeyRow(action = "Redo", desc = "Re-apply change", textColor = textColor, secondaryTextColor = secondaryTextColor)
                            SoftKeyRow(action = "Open", desc = "Open file", textColor = textColor, secondaryTextColor = secondaryTextColor)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { showKeyboardShortcutsDialog = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = textColor)
                    ) {
                        Text("Dismiss", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // Restore Unsaved Session Dialog
        if (showRestoreDraftDialog) {
            val prefs = getSharedPreferences("nano_gui_prefs", Context.MODE_PRIVATE)
            val draftFilename = prefs.getString("draft_filename", "Untitled") ?: "Untitled"
            val draftUriStr = prefs.getString("draft_uri", null)
            val draftIsModified = prefs.getBoolean("draft_is_modified", false)

            AlertDialog(
                onDismissRequest = { showRestoreDraftDialog = false },
                title = {
                    Text(
                        text = "Restore Session",
                        color = textColor,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                containerColor = surfaceColor,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                modifier = Modifier.border(width = 1.dp, color = borderColor, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                text = {
                    Text(
                        text = "An unsaved draft for '$draftFilename' was found from your last session. Would you like to restore it?",
                        color = secondaryTextColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val draftText = loadAutosaveDraft()
                            if (draftText != null) {
                                textValue = TextFieldValue(draftText)
                                engine.textState.value = draftText
                                currentFileName = draftFilename
                                currentUri = draftUriStr?.let { Uri.parse(it) }
                                isModified = draftIsModified
                                fileExtension = getFileExtension(draftFilename)
                                triggerBackgroundAnalysis(draftFilename, draftText)
                                Toast.makeText(this@MainActivity, "Session restored", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@MainActivity, "Failed to restore session", Toast.LENGTH_SHORT).show()
                            }
                            showRestoreDraftDialog = false
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = textColor)
                    ) {
                        Text("Restore", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            clearAutosave()
                            showRestoreDraftDialog = false
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = secondaryTextColor)
                    ) {
                        Text("Discard", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }

    @Composable
    private fun ShortcutRow(key: String, desc: String, textColor: Color, secondaryTextColor: Color) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = key,
                color = textColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = desc,
                color = secondaryTextColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.weight(1.5f)
            )
        }
    }

    @Composable
    private fun SoftKeyRow(action: String, desc: String, textColor: Color, secondaryTextColor: Color) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = action,
                color = textColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = desc,
                color = secondaryTextColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.weight(1.5f)
            )
        }
    }

    @Composable
    fun NanoShortcutButton(
        shortcut: String,
        label: String,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        val isDark = isSystemInDarkTheme()
        val textColor = if (isDark) Color.White else Color.Black
        val buttonBgColor = if (isDark) Color(0xFF1E1E1E) else Color(0xFFEBEBEB)

        Box(
            modifier = modifier
                .padding(horizontal = 2.dp, vertical = 2.dp)
                .height(36.dp)
                .background(color = buttonBgColor, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = textColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }

    @Composable
    fun HeaderMenuButton(
        shortcut: String,
        label: String,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        val isDark = isSystemInDarkTheme()
        val textColor = if (isDark) Color.White else Color.Black
        val secondaryTextColor = if (isDark) Color.LightGray else Color.DarkGray
        val borderColor = if (isDark) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.3f)
        val backgroundColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)

        Row(
            modifier = modifier
                .heightIn(min = 36.dp)
                .background(
                    color = backgroundColor,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                )
                .border(
                    width = 1.dp,
                    color = borderColor,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = shortcut,
                color = textColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
            Text(
                text = label,
                color = secondaryTextColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
