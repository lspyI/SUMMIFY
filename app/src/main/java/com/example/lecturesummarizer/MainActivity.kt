package com.example.lecturesummarizer

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas as PdfCanvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.lecturesummarizer.data.Folder
import com.example.lecturesummarizer.data.Lecture
import com.example.lecturesummarizer.data.LectureDao
import com.example.lecturesummarizer.data.LectureDatabase
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.OutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dao = LectureDatabase.getDatabase(this).lectureDao()
        val autoStart = intent.getBooleanExtra("START_RECORDING", false)
        setContent { SummifyTheme { LectureSummarizerApp(dao, autoStart) } }
    }
}

// --- COLORS ---
val SummifyBg = Color(0xFF0B1117)
val SummifySurface = Color(0xFF161B22).copy(alpha = 0.85f)
val SummifyBorder = Color(0xFF30363D)
val SummifyAccent = Color(0xFF00B4D8)
val SummifyHighlighter = Color(0xFF00B4D8).copy(alpha = 0.25f)
val SummifyError = Color(0xFFEF5350)

@Composable
fun SummifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = SummifyAccent, surface = SummifySurface, background = SummifyBg, onBackground = Color.White, onSurface = Color.White), content = content)
}

data class QuizItem(val q: String, val a: String)

// --- VIEWMODEL ---

class LectureViewModel(private val dao: LectureDao) : ViewModel() {
    private val _transcript = MutableStateFlow("")
    val transcript = _transcript.asStateFlow()
    private val _summary = MutableStateFlow("Здесь появится ваш конспект...")
    val summary = _summary.asStateFlow()
    private val _keywords = MutableStateFlow<List<String>>(emptyList())
    val keywords = _keywords.asStateFlow()
    private val _isRecording = RecordingService.isServiceRunning
    val isRecording = _isRecording.asStateFlow()
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing = _isProcessing.asStateFlow()
    private val _rmsVolume = RecordingService.rmsFlow
    val rmsVolume = _rmsVolume.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders = _folders.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredHistory = _searchQuery.flatMapLatest { q ->
        if (q.isBlank()) dao.getAllLectures() else dao.searchLectures("%$q%")
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _quiz = MutableStateFlow<List<QuizItem>>(emptyList())
    val quiz = _quiz.asStateFlow()
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var geminiJob: Job? = null
    private var newTextBuffer = ""
    private var currentAudioPath: String? = null

    private val generativeModel = GenerativeModel(
        modelName = "gemini-3.6-flash", apiKey = "ВАШ_API_КЛЮЧ_ЗДЕСЬ",
        systemInstruction = content { text("Ты — профессиональный стенографист SUMMIFY. Твоя задача: превращать сырой текст в чистый конспект. ПРАВИЛА: 1. Пиши только суть. 2. Никакого Markdown. 3. Если фрагмент помечен [ВАЖНО], выдели его.") }
    )

    init {
        viewModelScope.launch { dao.getAllFolders().collect { _folders.value = it } }
        viewModelScope.launch { RecordingService.transcriptFlow.collect { text -> appendTranscript(text) } }
        viewModelScope.launch { RecordingService.lastAudioPath.collect { currentAudioPath = it } }
    }

    fun generateQuiz() {
        if (_summary.value.length < 50) return
        _isProcessing.value = true
        viewModelScope.launch {
            try {
                val res = generativeModel.generateContent("Создай 5 вопросов и ответов. Формат: ВОПРОС: текст ОТВЕТ: текст. Конспект: ${_summary.value}")
                res.text?.let { raw ->
                    _quiz.value = raw.split("ВОПРОС:").filter { it.contains("ОТВЕТ:") }.map {
                        val p = it.split("ОТВЕТ:")
                        QuizItem(p[0].trim(), p[1].trim())
                    }
                }
            } catch (e: Exception) { _error.value = "Ошибка квиза" } finally { _isProcessing.value = false }
        }
    }

    fun playAudio() {
        val path = currentAudioPath ?: return
        try {
            if (_isPlaying.value) { mediaPlayer?.pause(); _isPlaying.value = false }
            else {
                if (mediaPlayer == null) mediaPlayer = MediaPlayer().apply { setDataSource(path); prepare(); setOnCompletionListener { _isPlaying.value = false } }
                mediaPlayer?.start(); _isPlaying.value = true
            }
        } catch (e: Exception) { Log.e("Player", "Error: ${e.message}") }
    }

    fun addBookmark() { newTextBuffer += " [ВАЖНО] "; forceUpdate() }
    fun updateSearch(q: String) { _searchQuery.value = q }
    fun createFolder(name: String) { viewModelScope.launch { dao.insertFolder(Folder(name = name)) } }
    fun deleteFolder(f: Folder) { viewModelScope.launch { dao.deleteLecturesInFolder(f.id); dao.deleteFolder(f) } }
    fun deleteLecture(l: Lecture) { viewModelScope.launch { dao.deleteLecture(l) } }
    fun moveLecture(l: Lecture, fId: Int?) { viewModelScope.launch { dao.updateLectureFolder(l.id, fId) } }
    fun updateLectureManual(id: Int, title: String, sum: String) {
        viewModelScope.launch {
            val list = dao.getAllLectures().first()
            val cur = list.find { it.id == id } ?: return@launch
            dao.updateLecture(cur.copy(title = title, summary = sum))
            if (_summary.value == cur.summary) _summary.value = sum
        }
    }

    fun setRecordingState(active: Boolean, context: Context) {
        if (active) { _transcript.value = ""; _summary.value = ""; _keywords.value = emptyList(); _quiz.value = emptyList(); newTextBuffer = ""; RecordingService.startService(context); startPeriodicSummary() }
        else { RecordingService.stopService(context); geminiJob?.cancel(); generateTitleAndSave() }
    }

    private fun startPeriodicSummary() { geminiJob = viewModelScope.launch { while (_isRecording.value) { delay(60000); if (newTextBuffer.isNotBlank()) updateSummaryFromGemini() } } }
    fun forceUpdate() { viewModelScope.launch { if (newTextBuffer.isNotBlank()) updateSummaryFromGemini() } }

    private suspend fun updateSummaryFromGemini() {
        _isProcessing.value = true; _error.value = null; val txt = newTextBuffer; newTextBuffer = ""
        try {
            val res = generativeModel.generateContent("Конспект и теги KEYWORDS: $txt")
            res.text?.let { raw ->
                var clean = raw
                if (raw.contains("KEYWORDS:")) { val p = raw.split("KEYWORDS:"); clean = p[0].trim(); _keywords.value = (_keywords.value + p[1].split(",").map { it.trim() }).distinct().take(10) }
                val cur = if (_summary.value.startsWith("Здесь")) "" else _summary.value; _summary.value = if (cur.isEmpty()) clean else "$cur\n\n$clean"
            }
        } catch (e: Exception) { _error.value = "Ошибка ИИ"; newTextBuffer = txt + " " + newTextBuffer } finally { _isProcessing.value = false }
    }

    private fun appendTranscript(text: String) { _transcript.value += " $text"; newTextBuffer += " $text" }

    private fun generateTitleAndSave() {
        viewModelScope.launch {
            var title = "Новая лекция"
            if (_summary.value.length > 50) { try { title = generativeModel.generateContent("Краткое название (3 слова): ${_summary.value.take(500)}").text?.trim() ?: title } catch (e: Exception) {} }
            dao.insertLecture(Lecture(title = title, summary = _summary.value, transcript = _transcript.value, keywords = _keywords.value.joinToString(","), timestamp = System.currentTimeMillis(), audioPath = currentAudioPath))
        }
    }
    fun loadLecture(l: Lecture) { _transcript.value = l.transcript; _summary.value = l.summary; _keywords.value = l.keywords.split(",").filter { it.isNotEmpty() }; currentAudioPath = l.audioPath; mediaPlayer?.release(); mediaPlayer = null; _isPlaying.value = false }
    override fun onCleared() { mediaPlayer?.release() }
}

class LectureViewModelFactory(private val dao: LectureDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = LectureViewModel(dao) as T
}

// --- UI COMPONENTS ---

@Composable
fun Waveform(rms: Float) {
    val t = rememberInfiniteTransition(label = "wave")
    val animScale by animateFloatAsState(targetValue = 1f + (rms / 10f).coerceIn(0f, 2f), label = "scale")
    Row(modifier = Modifier.height(20.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(15) { i ->
            val h by t.animateFloat(4f, 12f * animScale, animationSpec = infiniteRepeatable(tween(300 + i * 50), RepeatMode.Reverse), label = "bar")
            Box(Modifier.padding(horizontal = 1.dp).width(2.dp).height(h.dp).background(SummifyAccent, RoundedCornerShape(1.dp)))
        }
    }
}

@Composable
fun GridBackground() {
    val t = rememberInfiniteTransition(label = "glow"); val a by t.animateFloat(0.05f, 0.12f, infiniteRepeatable(tween(5000), RepeatMode.Reverse), label = "alpha")
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(Brush.radialGradient(listOf(SummifyAccent.copy(a), Color.Transparent), Offset(0f, 0f), size.width * 1.5f), size.width * 1.5f, Offset(0f, 0f))
        drawCircle(Brush.radialGradient(listOf(Color(0xFF7209B7).copy(a * 0.6f), Color.Transparent), Offset(size.width, size.height), size.width * 1.2f), size.width * 1.2f, Offset(size.width, size.height))
        val s = 40.dp.toPx(); val c = SummifyAccent.copy(0.16f)
        for (x in 0..size.width.toInt() step s.toInt()) drawLine(c, Offset(x.toFloat(), 0f), Offset(x.toFloat(), size.height))
        for (y in 0..size.height.toInt() step s.toInt()) drawLine(c, Offset(0f, y.toFloat()), Offset(size.width, y.toFloat()))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LectureSummarizerApp(dao: LectureDao, autoStart: Boolean) {
    val vm: LectureViewModel = viewModel(factory = LectureViewModelFactory(dao))
    val ctx = LocalContext.current
    val transcript by vm.transcript.collectAsState(); val summary by vm.summary.collectAsState(); val isRec by vm.isRecording.collectAsState(); val isProc by vm.isProcessing.collectAsState(); val rms by vm.rmsVolume.collectAsState(); val apiError by vm.error.collectAsState(); val keywords by vm.keywords.collectAsState(); val folders by vm.folders.collectAsState(); val history by vm.filteredHistory.collectAsState(); val query by vm.searchQuery.collectAsState(); val quizItems by vm.quiz.collectAsState(); val isPlay by vm.isPlaying.collectAsState()

    var showExitDialog by remember { mutableStateOf(false) }; var showFolderCreateDialog by remember { mutableStateOf(false) }; var newFolderName by remember { mutableStateOf("") }; var showMoveDialog by remember { mutableStateOf<Lecture?>(null) }; var showLectureDeleteDialog by remember { mutableStateOf<Lecture?>(null) }; var showFolderDeleteDialog1 by remember { mutableStateOf<Folder?>(null) }; var showFolderDeleteDialog2 by remember { mutableStateOf<Folder?>(null) }; var isEditMode by remember { mutableStateOf(false) }; var editTitle by remember { mutableStateOf("") }; var editSummary by remember { mutableStateOf("") }; var editId by remember { mutableStateOf<Int?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed); val scope = rememberCoroutineScope(); val act = ctx as? Activity

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { ctx.contentResolver.openOutputStream(it)?.use { out ->
            val doc = PdfDocument(); val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create()); val p = Paint().apply { textSize = 12f }
            page.canvas.drawText("SUMMIFY NOTES", 50f, 50f, p.apply { isFakeBoldText = true }); page.canvas.drawText(summary.take(100), 50f, 100f, p.apply { isFakeBoldText = false }); doc.finishPage(page); doc.writeTo(out); doc.close()
            Toast.makeText(ctx, "PDF OK", Toast.LENGTH_SHORT).show()
        } }
    }

    BackHandler(enabled = isRec) { showExitDialog = true }

    LaunchedEffect(Unit) { if (autoStart) { val perms = mutableListOf(Manifest.permission.RECORD_AUDIO); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS); (ctx as? ComponentActivity)?.requestPermissions(perms.toTypedArray(), 101); vm.setRecordingState(true, ctx) } }

    if (showExitDialog) AlertDialog(onDismissRequest = { showExitDialog = false }, containerColor = SummifySurface, title = { Text("Выйти?", color = Color.White) }, confirmButton = { TextButton(onClick = { act?.finish() }) { Text("ВЫЙТИ", color = SummifyError) } }, dismissButton = { TextButton(onClick = { showExitDialog = false }) { Text("НЕТ", color = SummifyAccent) } })
    if (showFolderCreateDialog) AlertDialog(onDismissRequest = { showFolderCreateDialog = false }, containerColor = SummifySurface, title = { Text("Новая папка", color = Color.White) }, text = { TextField(value = newFolderName, onValueChange = { newFolderName = it }, colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedTextColor = Color.White, unfocusedTextColor = Color.White)) }, confirmButton = { TextButton(onClick = { if (newFolderName.isNotBlank()) vm.createFolder(newFolderName); newFolderName = ""; showFolderCreateDialog = false }) { Text("OK") } })
    showLectureDeleteDialog?.let { AlertDialog(onDismissRequest = { showLectureDeleteDialog = null }, containerColor = SummifySurface, title = { Text("Удалить конспект?", color = Color.White) }, confirmButton = { TextButton(onClick = { vm.deleteLecture(it); showLectureDeleteDialog = null }) { Text("УДАЛИТЬ", color = SummifyError) } }) }
    showFolderDeleteDialog1?.let { f -> AlertDialog(onDismissRequest = { showFolderDeleteDialog1 = null }, containerColor = SummifySurface, title = { Text("Удалить папку?", color = Color.White) }, confirmButton = { TextButton(onClick = { showFolderDeleteDialog2 = f; showFolderDeleteDialog1 = null }) { Text("ДА") } }, dismissButton = { TextButton(onClick = { showFolderDeleteDialog1 = null }) { Text("НЕТ") } }) }
    showFolderDeleteDialog2?.let { f -> AlertDialog(onDismissRequest = { showFolderDeleteDialog2 = null }, containerColor = SummifySurface, title = { Text("ТОТАЛЬНОЕ УДАЛЕНИЕ", color = SummifyError) }, text = { Text("Все лекции в папке будут удалены.", color = Color.White) }, confirmButton = { TextButton(onClick = { vm.deleteFolder(f); showFolderDeleteDialog2 = null }) { Text("УДАЛИТЬ ВСЁ", color = SummifyError) } }, dismissButton = { TextButton(onClick = { showFolderDeleteDialog2 = null }) { Text("ОТМЕНА") } }) }
    if (isEditMode) AlertDialog(onDismissRequest = { isEditMode = false }, containerColor = SummifySurface, title = { Text("Правка", color = SummifyAccent) }, text = { Column { TextField(value = editTitle, onValueChange = { editTitle = it }, label = { Text("Заголовок") }); TextField(value = editSummary, onValueChange = { editSummary = it }, label = { Text("Текст") }) } }, confirmButton = { TextButton(onClick = { editId?.let { vm.updateLectureManual(it, editTitle, editSummary) }; isEditMode = false }) { Text("OK") } })
    showMoveDialog?.let { l -> AlertDialog(onDismissRequest = { showMoveDialog = null }, containerColor = SummifySurface, title = { Column { Text("Переместить", color = Color.White); Text(l.title, fontSize = 12.sp, color = Color.Gray) } }, text = { Column { TextButton(onClick = { vm.moveLecture(l, null); showMoveDialog = null }) { Text("Без папки", color = Color.Gray) }; folders.forEach { f -> TextButton(onClick = { vm.moveLecture(l, f.id); showMoveDialog = null }) { Text(f.name, color = SummifyAccent) } } } }, confirmButton = {}) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(280.dp), drawerContainerColor = SummifyBg, drawerContentColor = Color.White) {
                Row(modifier = Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("ИСТОРИЯ", fontWeight = FontWeight.Bold, color = SummifyAccent); IconButton(onClick = { showFolderCreateDialog = true }) { Icon(Icons.Default.CreateNewFolder, null, tint = SummifyAccent) } }
                TextField(value = query, onValueChange = { vm.updateSearch(it) }, placeholder = { Text("Поиск...") }, modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), colors = TextFieldDefaults.colors(focusedContainerColor = SummifySurface, unfocusedContainerColor = SummifySurface, focusedTextColor = Color.White, unfocusedTextColor = Color.White), shape = RoundedCornerShape(12.dp), leadingIcon = { Icon(Icons.Default.Search, null) })
                Spacer(Modifier.height(16.dp)); HorizontalDivider(color = SummifyBorder)
                LazyColumn(Modifier.fillMaxSize()) {
                    items(folders) { f ->
                        var ex by remember { mutableStateOf(false) }
                        Column {
                            Row(modifier = Modifier.fillMaxWidth().clickable { ex = !ex }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (ex) Icons.Default.FolderOpen else Icons.Default.Folder, null, tint = SummifyAccent); Spacer(Modifier.width(12.dp)); Text(f.name, Modifier.weight(1f)); IconButton(onClick = { showFolderDeleteDialog1 = f }) { Icon(Icons.Default.Delete, null, tint = SummifyError.copy(0.3f)) } }
                            if (ex) history.filter { it.folderId == f.id }.forEach { l -> LectureItem(l, vm, scope, drawerState, true, { showMoveDialog = it }, { showLectureDeleteDialog = it }) }
                        }
                    }
                    item { Text("БЕЗ ПАПКИ", Modifier.padding(16.dp), fontSize = 10.sp, color = Color.Gray) }
                    items(history.filter { it.folderId == null }) { l -> LectureItem(l, vm, scope, drawerState, false, { showMoveDialog = it }, { showLectureDeleteDialog = it }) }
                }
            }
        }
    ) {
        Box(Modifier.fillMaxSize().background(SummifyBg)) {
            GridBackground()
            Scaffold(containerColor = Color.Transparent, topBar = { CenterAlignedTopAppBar(navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, null, tint = Color.White) } }, title = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(painterResource(R.drawable.ic_logo), null, tint = Color.Unspecified, modifier = Modifier.size(26.dp)); Spacer(Modifier.width(10.dp)); Text("SUMMIFY", fontWeight = FontWeight.Black, letterSpacing = 2.sp) } }, colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)) }) { padding ->
                Column(modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    StatusCard(isRec, isProc, rms)
                    if (keywords.isNotEmpty()) LazyRow(Modifier.padding(vertical = 12.dp)) { items(keywords) { tag -> Surface(color = SummifyHighlighter, shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(end = 6.dp)) { Text(tag, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 10.sp, color = SummifyAccent, fontWeight = FontWeight.Bold) } } }
                    apiError?.let { Text(it, color = SummifyError, fontSize = 11.sp) }
                    Box(Modifier.weight(0.8f)) {
                        SummaryBox(
                            text = summary, modifier = Modifier.fillMaxSize(),
                            onCopy = { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Summary", summary)); Toast.makeText(ctx, "OK", Toast.LENGTH_SHORT).show() },
                            onShare = { val i = Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_TEXT, summary); type = "text/plain" }; ctx.startActivity(Intent.createChooser(i, "Share")) },
                            onUrgent = { vm.forceUpdate() }, onPdf = { pdfLauncher.launch("Summify.pdf") }, onBookmark = { vm.addBookmark() },
                            onEdit = { val cur = history.find { it.summary == summary }; editId = cur?.id; editTitle = cur?.title ?: "Лекция"; editSummary = summary; isEditMode = true },
                            onQuiz = { vm.generateQuiz() }, onPlay = { vm.playAudio() },
                            isRecording = isRec, isPlaying = isPlay, hasQuiz = quizItems.isNotEmpty()
                        )
                    }
                    if (quizItems.isNotEmpty()) Surface(Modifier.fillMaxWidth().padding(top = 8.dp), color = SummifySurface, shape = RoundedCornerShape(12.dp), border = BorderStroke(0.5.dp, SummifyAccent)) { Column(Modifier.padding(12.dp)) { Text("КВИЗ ГОТОВ", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = SummifyAccent); Text("Нажмите иконку мозга.", fontSize = 11.sp, color = Color.White.copy(0.7f)) } }
                    Spacer(Modifier.height(16.dp)); TranscriptBox(transcript, Modifier.height(70.dp)); Spacer(Modifier.height(20.dp))
                    Button(onClick = { vm.setRecordingState(!isRec, ctx) }, modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(20.dp), colors = ButtonDefaults.buttonColors(containerColor = (if (isRec) SummifyError else SummifyAccent).copy(alpha = 0.15f)), contentPadding = PaddingValues(0.dp)) { Box(Modifier.fillMaxSize().border(1.dp, if (isRec) SummifyError else SummifyAccent, RoundedCornerShape(20.dp)), Alignment.Center) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(if (isRec) Icons.Default.MicOff else Icons.Default.Mic, null, tint = if (isRec) SummifyError else SummifyAccent); Spacer(Modifier.width(12.dp)); Text(if (isRec) "ЗАКОНЧИТЬ" else "НАЧАТЬ ЛЕКЦИЮ", fontWeight = FontWeight.Bold, color = if (isRec) SummifyError else SummifyAccent) } } }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LectureItem(l: Lecture, vm: LectureViewModel, scope: kotlinx.coroutines.CoroutineScope, d: DrawerState, indent: Boolean, onLong: (Lecture) -> Unit, onDel: (Lecture) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { vm.loadLecture(l); scope.launch { d.close() } }, onLongClick = { onLong(l) }).padding(horizontal = 24.dp, vertical = 12.dp).padding(start = if (indent) 16.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(l.title, fontSize = 14.sp, color = Color.White, maxLines = 1); Text(android.text.format.DateFormat.format("dd.MM HH:mm", l.timestamp).toString(), fontSize = 10.sp, color = Color.Gray) }
        IconButton(onClick = { onDel(l) }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Delete, null, tint = SummifyError.copy(alpha = 0.5f), modifier = Modifier.size(16.dp)) }
    }
}

@Composable
fun StatusCard(isRec: Boolean, isProc: Boolean, rms: Float) {
    Surface(color = SummifySurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, SummifyBorder), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isRec) Waveform(rms) else Box(modifier = Modifier.size(8.dp).background(Color.Gray, RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(16.dp)); Text(if (isRec) "В ЭФИРЕ" else "ГОТОВ", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (isRec) Color.White else Color.Gray)
            Spacer(Modifier.weight(1f)); if (isProc) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = SummifyAccent)
        }
    }
}

@Composable
fun SummaryBox(text: String, modifier: Modifier, onCopy: () -> Unit, onShare: () -> Unit, onUrgent: () -> Unit, onPdf: () -> Unit, onBookmark: () -> Unit, onEdit: () -> Unit, onQuiz: () -> Unit, onPlay: () -> Unit, isRecording: Boolean, isPlaying: Boolean, hasQuiz: Boolean) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("КОНСПЕКТ", fontSize = 11.sp, color = SummifyAccent, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isRecording) IconButton(onClick = onBookmark) { Icon(Icons.Default.Star, null, tint = Color.Yellow) }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, null, tint = Color.White.copy(0.5f), modifier = Modifier.size(16.dp)) }
                IconButton(onClick = onQuiz) { Icon(Icons.Default.Psychology, null, tint = if (hasQuiz) SummifyAccent else Color.White.copy(0.5f), modifier = Modifier.size(18.dp)) }
                IconButton(onClick = onPlay) { Icon(if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle, null, tint = SummifyAccent) }
                TextButton(onClick = onUrgent) { Icon(Icons.Default.FlashOn, null, Modifier.size(14.dp)); Text(" СРОЧНО", fontSize = 10.sp) }
                IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, null, tint = Color.White.copy(0.5f), modifier = Modifier.size(16.dp)) }
                IconButton(onClick = onShare) { Icon(Icons.Default.Share, null, tint = Color.White.copy(0.5f), modifier = Modifier.size(16.dp)) }
            }
        }
        Surface(modifier = Modifier.fillMaxSize(), color = SummifySurface, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, SummifyBorder)) { Box(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) { Text(text, fontSize = 14.sp, lineHeight = 22.sp, color = Color.White) } }
    }
}

@Composable
fun TranscriptBox(text: String, modifier: Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), color = SummifySurface.copy(alpha = 0.3f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, SummifyBorder.copy(alpha = 0.3f))) { Box(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) { Text(if (text.isEmpty()) "Ожидание..." else text, fontSize = 11.sp, color = Color.Gray) } }
}
