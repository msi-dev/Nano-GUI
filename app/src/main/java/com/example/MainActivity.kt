package com.example

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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val engine = EditorEngine()

    private val currentUriState = mutableStateOf<Uri?>(null)
    private val currentFileNameState = mutableStateOf<String?>("Untitled")
    private val fileExtensionState = mutableStateOf("")
    private val isModifiedState = mutableStateOf(false)
    private val textValueState = mutableStateOf(TextFieldValue(""))

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
        fileExtensionState.value = getFileExtension(currentFileNameState.value ?: "")
        if (engine.loadFromUri(this, uri)) {
            textValueState.value = TextFieldValue(engine.textState.value)
            isModifiedState.value = false
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
                    fileExtension = getFileExtension(currentFileName ?: "")
                    if (engine.loadFromUri(this@MainActivity, it)) {
                        textValue = TextFieldValue(engine.textState.value)
                        isModified = false
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

        // Request keyboard focus
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .background(backgroundColor)
        ) {
            // Top Status Bar (Immersive UI style)
            val text = textValue.text
            val selectionStart = textValue.selection.start
            val substringBefore = text.substring(0, selectionStart.coerceIn(0, text.length))
            val linesBefore = substringBefore.count { it == '\n' } + 1
            val lastLineStart = substringBefore.lastIndexOf('\n')
            val col = if (lastLineStart == -1) selectionStart + 1 else selectionStart - lastLineStart

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(surfaceColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .border(width = 1.dp, color = borderColor),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(textColor.copy(alpha = 0.1f), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "GNU nano 7.2",
                            color = textColor,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = currentFileName ?: "Untitled",
                        color = textColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(14.dp)
                            .background(borderColor)
                    )
                    Text(
                        text = "Ln $linesBefore, Col $col",
                        color = secondaryTextColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }

            // Static Header Menu (mimics nano interface reference commands)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(surfaceColor)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .border(width = 1.dp, color = borderColor),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeaderMenuButton(
                    shortcut = "^G",
                    label = "Help",
                    onClick = { showHelpDialog = true }
                )
                HeaderMenuButton(
                    shortcut = "^O",
                    label = "WriteOut",
                    onClick = onSaveAction
                )
                HeaderMenuButton(
                    shortcut = "^R",
                    label = "ReadFile",
                    onClick = { openDocumentLauncher.launch(arrayOf("text/*")) }
                )
                HeaderMenuButton(
                    shortcut = "^W",
                    label = "WhereIs",
                    onClick = onSearchAction
                )
            }

            // Gutter + Text Field
            val density = LocalDensity.current
            val lineDp = 22.dp
            val lineHeightPx = with(density) { lineDp.toPx() }

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(backgroundColor)
            ) {
                val totalLines = textValue.text.split("\n").size
                val scrollOffset = scrollState.value
                val firstVisibleIndex = (scrollOffset / lineHeightPx).toInt().coerceIn(0, totalLines - 1)
                
                // Buffer to keep render performance high
                val visibleLinesCount = 60
                val lastVisibleIndex = (firstVisibleIndex + visibleLinesCount).coerceAtMost(totalLines - 1)

                Row(modifier = Modifier.fillMaxSize()) {
                    // Line Number Gutter
                    Column(
                        modifier = Modifier
                            .width(44.dp)
                            .fillMaxHeight()
                            .background(surfaceColor)
                            .verticalScroll(scrollState)
                            .padding(end = 6.dp, top = 4.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Spacer(modifier = Modifier.height(lineDp * firstVisibleIndex))

                        for (i in (firstVisibleIndex + 1)..(lastVisibleIndex + 1)) {
                            Text(
                                text = "$i",
                                color = secondaryTextColor,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
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
                            fontSize = 14.sp,
                            lineHeight = 22.sp
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

            // Bottom Status Bar (displays 'Nano Editor - New File' and helpful keyboard shortcuts)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(surfaceColor)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .border(width = 1.dp, color = borderColor),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Nano Editor - ${currentFileName ?: "New File"}",
                    color = textColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "^G: Help | ^O: WriteOut | ^R: ReadFile | ^X: Exit",
                    color = secondaryTextColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }

            // Bottom Commands Bar (Immersive UI style)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(surfaceColor)
                    .padding(horizontal = 6.dp, vertical = 8.dp)
                    .border(width = 1.dp, color = borderColor)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                        label = "WriteOut",
                        onClick = onSaveAction,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("save_button")
                    )
                    NanoShortcutButton(
                        shortcut = "^W",
                        label = "Where Is",
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
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                        label = "Read File",
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
        val secondaryTextColor = if (isDark) Color.LightGray else Color.DarkGray

        Row(
            modifier = modifier
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp, horizontal = 2.dp)
                .heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = shortcut,
                color = textColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                modifier = Modifier.padding(end = 4.dp)
            )
            Text(
                text = label,
                color = secondaryTextColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
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
