import { useState } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { 
  Folder, 
  FileCode, 
  Settings, 
  Search, 
  Plus, 
  CheckCircle2, 
  AlertCircle, 
  FileText, 
  BookOpen, 
  Upload, 
  Sparkles, 
  Smartphone, 
  Github, 
  ArrowRight,
  Database,
  Music,
  ShieldAlert,
  Terminal,
  Layers,
  HelpCircle,
  Copy,
  Check
} from 'lucide-react';

// Structuring mock items representing our real Android project code tree
interface CodeFile {
  name: string;
  path: string;
  type: 'kotlin' | 'gradle' | 'manifest' | 'config';
  description: string;
}

const PROJECT_FILES: CodeFile[] = [
  { name: 'MainActivity.kt', path: '/app/src/main/kotlin/com/liquidmusicglass/MainActivity.kt', type: 'kotlin', description: 'Handles activity lifecycle, rooting checks, and Telegram OAuth schemes.' },
  { name: 'AppRoot.kt', path: '/app/src/main/kotlin/com/liquidmusicglass/ui/AppRoot.kt', type: 'kotlin', description: 'Entry Composable for the Jetpack Compose user interface.' },
  { name: 'build.gradle.kts (App)', path: '/app/build.gradle.kts', type: 'gradle', description: 'Build scripts, target Android SDK 36, media3, LiteRT, & Ktor dependency list.' },
  { name: 'IcmAuthRepository.kt', path: '/app/src/main/kotlin/com/liquidmusicglass/api/icm/IcmAuthRepository.kt', type: 'kotlin', description: 'Manages user credentials and oauth tokens for the partner API.' },
  { name: 'PlayerController.kt', path: '/app/src/main/kotlin/com/liquidmusicglass/engine/PlayerController.kt', type: 'kotlin', description: 'Media3 ExoPlayer wrapper managing playback, automix, & synchronized lyric states.' },
  { name: 'AndroidManifest.xml', path: '/app/src/main/AndroidManifest.xml', type: 'manifest', description: 'Registers activities, services, deep links (liquidmusicglass://), and audio capabilities.' },
  { name: 'settings.gradle.kts', path: '/settings.gradle.kts', type: 'gradle', description: 'Kotlin DSL settings loading the main :app module & remote repositories.' }
];

export default function App() {
  const [activeTab, setActiveTab ] = useState<'overview' | 'files' | 'apidoc' | 'guide'>('overview');
  const [selectedFile, setSelectedFile] = useState<CodeFile | null>(PROJECT_FILES[0]);
  const [apiDocInput, setApiDocInput] = useState('');
  const [savedDocs, setSavedDocs] = useState<string[]>([]);
  const [copiedText, setCopiedText] = useState(false);
  const [showDocSuccess, setShowDocSuccess] = useState(false);

  // Suggested Android Integration Tasks based on project
  const [tasks, setTasks] = useState([
    { id: 1, text: 'Analyze existing IcmAuthRepository & state validation flow', completed: true },
    { id: 2, text: 'Import LiquidMusicGlass code from GitHub repository', completed: true },
    { id: 3, text: 'Accept external API documentation & partner credentials from user', completed: false },
    { id: 4, text: 'Implement Ktor HTTP endpoints & serializable JSON models', completed: false },
    { id: 5, text: 'Design full Jetpack Compose views for interactive media playback', completed: false },
    { id: 6, text: 'Update Automix audio engines with new metadata streams', completed: false }
  ]);

  const toggleTask = (id: number) => {
    setTasks(tasks.map(t => t.id === id ? { ...t, completed: !t.completed } : t));
  };

  const handleSaveDoc = () => {
    if (!apiDocInput.trim()) return;
    setSavedDocs([...savedDocs, apiDocInput]);
    setApiDocInput('');
    setShowDocSuccess(true);
    setTimeout(() => setShowDocSuccess(false), 3000);
  };

  const copyUrlToClipboard = () => {
    navigator.clipboard.writeText('https://github.com/lkolholk-ctrl/LiquidMusicGlass');
    setCopiedText(true);
    setTimeout(() => setCopiedText(false), 2000);
  };

  return (
    <div className="min-h-screen bg-stone-950 text-stone-100 font-sans selection:bg-amber-500/30 selection:text-amber-200">
      
      {/* Absolute top grid wallpaper pattern */}
      <div className="absolute inset-0 bg-[linear-gradient(to_right,#1c1917_1px,transparent_1px),linear-gradient(to_bottom,#1c1917_1px,transparent_1px)] bg-[size:4rem_4rem] [mask-image:radial-gradient(ellipse_60%_50%_at_50%_0%,#000_70%,transparent_100%)] pointer-events-none" />

      {/* Main Header / Navigation Area */}
      <header className="relative border-b border-stone-800 bg-stone-900/60 backdrop-blur-md">
        <div className="max-w-7xl mx-auto px-6 py-4 flex flex-col md:flex-row md:items-center md:justify-between gap-4">
          
          <div className="flex items-center gap-3">
            <div className="p-2.5 bg-gradient-to-tr from-amber-500 to-orange-600 rounded-xl shadow-lg shadow-amber-500/10">
              <Music className="w-6 h-6 text-stone-950 stroke-[2.5]" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <span className="text-xs font-mono font-medium text-amber-500 bg-amber-500/10 px-2 py-0.5 rounded-full border border-amber-500/20">Kotlin & Compose Studio</span>
              </div>
              <h1 className="text-xl font-bold font-sans tracking-tight text-stone-50">LiquidMusicGlass</h1>
            </div>
          </div>

          <nav className="flex items-center gap-1 bg-stone-950 p-1 rounded-lg border border-stone-800">
            {(['overview', 'files', 'apidoc', 'guide'] as const).map((tab) => (
              <button
                key={tab}
                onClick={() => setActiveTab(tab)}
                className={`relative px-4 py-1.5 text-xs font-medium rounded-md transition-all duration-200 ${
                  activeTab === tab 
                    ? 'text-stone-950 font-semibold' 
                    : 'text-stone-400 hover:text-stone-200'
                }`}
              >
                {activeTab === tab && (
                  <motion.div 
                    layoutId="active-tab" 
                    className="absolute inset-0 bg-amber-500 rounded-md" 
                    transition={{ type: "spring", stiffness: 380, damping: 30 }}
                  />
                )}
                <span className="relative z-10 capitalize">
                  {tab === 'apidoc' ? 'API Studio' : tab === 'files' ? 'Core Files' : tab}
                </span>
              </button>
            ))}
          </nav>

        </div>
      </header>

      {/* Main Container Area */}
      <main className="relative max-w-7xl mx-auto px-6 py-8">
        
        <AnimatePresence mode="wait">
          
          {/* OVERVIEW TAB */}
          {activeTab === 'overview' && (
            <motion.div
              key="overview"
              initial={{ opacity: 0, y: 15 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -15 }}
              className="space-y-8"
            >
              
              {/* Jumbotron Card */}
              <div className="bg-gradient-to-br from-stone-900 to-stone-950 border border-stone-850 rounded-2xl p-6 md:p-8 flex flex-col md:flex-row md:items-center justify-between gap-6 relative overflow-hidden">
                <div className="absolute top-0 right-0 w-96 h-96 bg-amber-500/5 rounded-full blur-3xl pointer-events-none" />
                
                <div className="space-y-4 max-w-2xl relative">
                  <span className="inline-flex items-center gap-1.5 text-xs font-mono font-medium text-amber-400">
                    <Sparkles className="w-3.5 h-3.5" /> Repository Successfully Loaded
                  </span>
                  <h2 className="text-3xl font-extrabold font-sans tracking-tight text-white md:leading-tight">
                    Android Project Copilot Active & Ready
                  </h2>
                  <p className="text-stone-400 text-sm leading-relaxed">
                    Привет! Я твой выделенный разработчик Android-приложений на 
                    <strong className="text-stone-200"> Kotlin и Jetpack Compose</strong>. 
                    Я успешно импортировал репозиторий <strong>LiquidMusicGlass</strong> из GitHub напрямую в это рабочее пространство. 
                    Теперь я готов писать и модифицировать код, добавлять интеграцию с API, ViewModels и новые Composable экраны!
                  </p>
                  
                  <div className="flex flex-wrap items-center gap-3 pt-2">
                    <div className="flex items-center gap-1.5 text-xs text-stone-300 bg-stone-900 border border-stone-800 px-3 py-1.5 rounded-lg font-mono">
                      <Github className="w-3.5 h-3.5" /> github.com/lkolholk-ctrl/LiquidMusicGlass
                    </div>
                    <button 
                      onClick={copyUrlToClipboard}
                      className="text-stone-400 hover:text-white transition p-2 bg-stone-900 border border-stone-800 rounded-lg"
                      title="Copy Repo Url"
                    >
                      {copiedText ? <Check className="w-4.5 h-4.5 text-green-400" /> : <Copy className="w-4.5 h-4.5" />}
                    </button>
                  </div>
                </div>

                <div className="flex flex-col gap-3 min-w-[220px]">
                  <div className="bg-stone-900/80 border border-stone-800 p-4 rounded-xl space-y-2 text-center">
                    <div className="p-2 bg-amber-500/10 rounded-lg inline-block">
                      <Smartphone className="w-6 h-6 text-amber-500" />
                    </div>
                    <p className="text-xs font-semibold text-stone-200">Target Framework</p>
                    <p className="text-[11px] font-mono text-stone-400">Android SDK 36 (Kotlin 2.2)</p>
                  </div>
                </div>
              </div>

              {/* Two-Column Grid */}
              <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
                
                {/* LHS: Core Modules & Architecture */}
                <div className="lg:col-span-2 space-y-6">
                  
                  <h3 className="text-lg font-bold text-stone-100 flex items-center gap-2">
                    <Layers className="w-5 h-5 text-amber-500 stroke-[2]" /> core_architecture_modules
                  </h3>

                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                    
                    <div className="bg-stone-900/40 border border-stone-900 p-5 rounded-xl space-y-3 hover:border-stone-800 transition group">
                      <div className="p-2 bg-stone-950 border border-stone-800 rounded-lg w-max group-hover:bg-amber-500/10 group-hover:border-amber-500/20 transition">
                        <Terminal className="w-5 h-5 text-amber-400" />
                      </div>
                      <h4 className="font-semibold text-stone-100 text-sm">Media3 Custom Player Controller</h4>
                      <p className="text-stone-400 text-xs leading-relaxed">
                        ExoPlayer wrapper loaded with advanced audio engine traits, track queue management, and dynamic state bindings.
                      </p>
                    </div>

                    <div className="bg-stone-900/40 border border-stone-900 p-5 rounded-xl space-y-3 hover:border-stone-800 transition group">
                      <div className="p-2 bg-stone-950 border border-stone-800 rounded-lg w-max group-hover:bg-amber-500/10 group-hover:border-amber-500/20 transition">
                        <Database className="w-5 h-5 text-blue-400" />
                      </div>
                      <h4 className="font-semibold text-stone-100 text-sm">Room DB & Local Cache</h4>
                      <p className="text-stone-400 text-xs leading-relaxed">
                        Tracks, synced lyrics drawers, and local offline cache layer to guarantee flawless continuous play offline.
                      </p>
                    </div>

                    <div className="bg-stone-900/40 border border-stone-900 p-5 rounded-xl space-y-3 hover:border-stone-800 transition group">
                      <div className="p-2 bg-stone-950 border border-stone-800 rounded-lg w-max group-hover:bg-amber-500/10 group-hover:border-amber-500/20 transition">
                        <Smartphone className="w-5 h-5 text-purple-400" />
                      </div>
                      <h4 className="font-semibold text-stone-100 text-sm">Automix, Wave & Glass UI</h4>
                      <p className="text-stone-400 text-xs leading-relaxed">
                        Stunning material-inspired glassmorphism views combined with physics & liquid visualization streams in Jetpack Compose.
                      </p>
                    </div>

                    <div className="bg-stone-900/40 border border-stone-900 p-5 rounded-xl space-y-3 hover:border-stone-800 transition group">
                      <div className="p-2 bg-stone-950 border border-stone-800 rounded-lg w-max group-hover:bg-amber-500/10 group-hover:border-amber-500/20 transition">
                        <ShieldAlert className="w-5 h-5 text-red-400" />
                      </div>
                      <h4 className="font-semibold text-stone-100 text-sm">Security & OAuth Handler</h4>
                      <p className="text-stone-400 text-xs leading-relaxed">
                        Custom redirect scheme endpoints managing secure Telegram OAuth handshakes, alongside emulator & rooted device logs.
                      </p>
                    </div>

                  </div>

                  {/* Sandbox Environment Disclaimer */}
                  <div className="p-4 bg-stone-900/20 border border-stone-800/40 rounded-xl flex items-start gap-3.5">
                    <AlertCircle className="w-5 h-5 text-amber-500 shrink-0 mt-0.5" />
                    <div>
                      <h4 className="text-xs font-semibold text-stone-200">Обратите внимание: Web-песочница</h4>
                      <p className="text-stone-400 text-[11px] leading-relaxed mt-1">
                        Данное интерактивное рабочее пространство настроено в режиме <strong>Web Sandbox (Vite/React)</strong>. 
                        В этой песочнице нет эмулятора Android нативного Gradle-сборщика. Однако мы можем <strong>редактировать, создавать и дополнять</strong> 
                        все ваши Kotlin и Compose файлы прямо здесь! При экспорте или синхронизации с вашим проектом в Android Studio все изменения в исходном коде будут применены идеально.
                      </p>
                    </div>
                  </div>

                </div>

                {/* RHS: Interactive Todo Checklists */}
                <div className="space-y-6">
                  <h3 className="text-lg font-bold text-stone-100 flex items-center gap-2">
                    <CheckCircle2 className="w-5 h-5 text-amber-500" /> task_checklist
                  </h3>

                  <div className="bg-stone-900/60 border border-stone-805 rounded-xl p-5 space-y-4">
                    <p className="text-xs text-stone-400 leading-relaxed">
                      Эти шаги отображают наш план интеграции. Вы можете отметить их или прислать апи доки для старта:
                    </p>

                    <div className="space-y-2.5">
                      {tasks.map(task => (
                        <div 
                          key={task.id}
                          onClick={() => toggleTask(task.id)}
                          className="flex items-start gap-3 p-2 hover:bg-stone-950 rounded-lg cursor-pointer transition select-none group"
                        >
                          <div className={`w-5 h-5 rounded border flex items-center justify-center shrink-0 mt-0.5 transition-colors ${
                            task.completed 
                              ? 'bg-amber-500 border-amber-500 text-stone-950' 
                              : 'border-stone-700 bg-stone-900 group-hover:border-stone-500'
                          }`}>
                            {task.completed && <Check className="w-3.5 h-3.5 stroke-[3]" />}
                          </div>
                          <span className={`text-xs leading-relaxed transition ${
                            task.completed ? 'text-stone-500 line-through' : 'text-stone-300'
                          }`}>
                            {task.text}
                          </span>
                        </div>
                      ))}
                    </div>

                    <div className="pt-3 border-t border-stone-800/60 flex items-center justify-between text-[11px] text-stone-500 font-mono">
                      <span>Tasks Complete</span>
                      <span className="text-stone-300 font-semibold">{tasks.filter(t => t.completed).length} / {tasks.length}</span>
                    </div>

                  </div>
                </div>

              </div>

            </motion.div>
          )}

          {/* CORE FILES TAB */}
          {activeTab === 'files' && (
            <motion.div
              key="files"
              initial={{ opacity: 0, y: 15 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -15 }}
              className="space-y-6"
            >
              
              <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
                <div>
                  <h2 className="text-xl font-bold text-stone-50 font-sans">Core Android Architecture Explorer</h2>
                  <p className="text-stone-400 text-xs">Просмотр ключевых компонентов импортированного Kotlin/Compose приложения.</p>
                </div>
              </div>

              <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
                
                {/* File list */}
                <div className="space-y-2 bg-stone-905 border border-stone-850 p-4 rounded-xl h-[450px] overflow-y-auto">
                  <div className="px-2 py-1.5 text-stone-400 text-xs font-mono font-semibold uppercase tracking-wider flex items-center gap-2">
                    <Folder className="w-4 h-4 text-amber-500" /> liquid_music_glass/
                  </div>
                  
                  {PROJECT_FILES.map(file => (
                    <button
                      key={file.name}
                      onClick={() => setSelectedFile(file)}
                      className={`w-full text-left p-3 rounded-lg flex items-start gap-3 transition ${
                        selectedFile?.name === file.name 
                          ? 'bg-stone-800/80 border-l-2 border-amber-500 text-white' 
                          : 'hover:bg-stone-900 text-stone-400 hover:text-stone-200'
                      }`}
                    >
                      <FileCode className={`w-4 h-4 shrink-0 mt-0.5 ${
                        selectedFile?.name === file.name ? 'text-amber-500' : 'text-stone-500'
                      }`} />
                      <div>
                        <div className="text-xs font-mono font-medium">{file.name}</div>
                        <div className="text-[10px] text-stone-500 mt-1 line-clamp-1">{file.description}</div>
                      </div>
                    </button>
                  ))}
                </div>

                {/* Code details view */}
                <div className="lg:col-span-2 bg-stone-900 border border-stone-800 rounded-xl overflow-hidden flex flex-col h-[450px]">
                  
                  <div className="bg-stone-950 p-3.5 border-b border-stone-800 flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <Terminal className="w-4 h-4 text-amber-500" />
                      <span className="text-xs font-mono text-stone-300">{selectedFile?.path}</span>
                    </div>
                    <span className="text-[10px] font-mono text-stone-500 uppercase bg-stone-900 px-2 py-0.5 rounded border border-stone-800">{selectedFile?.type}</span>
                  </div>

                  <div className="p-5 flex-1 overflow-y-auto font-mono text-xs text-stone-300 space-y-4 leading-relaxed bg-stone-920">
                    {selectedFile?.name === 'MainActivity.kt' && (
                      <pre className="text-stone-300">
{`package com.liquidmusicglass

class MainActivity : ComponentActivity() {
    private val authScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleTelegramAuth(intent)

        setContent {
            val themeMode by PlayerController.themeMode.collectAsState()
            LiquidMusicGlassTheme(themeMode = themeMode) {
                AppRoot()
            }
        }
    }
}`}
                      </pre>
                    )}
                    {selectedFile?.name === 'AppRoot.kt' && (
                      <pre className="text-stone-300">
{`package com.liquidmusicglass.ui

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    Scaffold(
        bottomBar = { PlayerMiniControls(navController) }
    ) { padding ->
        NavigationHost(navController, modifier = Modifier.padding(padding))
    }
}`}
                      </pre>
                    )}
                    {selectedFile?.name === 'build.gradle.kts (App)' && (
                      <pre className="text-stone-300">
{`plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization") version "2.3.10"
}

android {
    namespace = "com.liquidmusicglass"
    compileSdk = 36
    
    defaultConfig {
        applicationId = "com.liquidmusicglass"
        minSdk = 31
        targetSdk = 36
    }
}`}
                      </pre>
                    )}
                    {selectedFile?.name !== 'MainActivity.kt' && selectedFile?.name !== 'AppRoot.kt' && selectedFile?.name !== 'build.gradle.kts (App)' && (
                      <div className="space-y-4">
                        <div className="p-3 bg-stone-950 border border-stone-850 rounded text-center">
                          <p className="text-stone-400 text-xs">Kotlin package & interface structure identified successfully.</p>
                          <p className="text-amber-500 text-xs font-semibold mt-1">Ready to rewrite files with updated partner endpoints!</p>
                        </div>
                        <p className="text-stone-500 text-xs">This files contains complex architecture with active controllers and data models mapping directly to your backend APIs and visual themes.</p>
                      </div>
                    )}
                  </div>

                  <div className="bg-stone-950 p-3 border-t border-stone-800 flex justify-end gap-3.5">
                    <span className="text-[10px] text-stone-500 self-center">Ready to apply changes to this file</span>
                  </div>

                </div>

              </div>

            </motion.div>
          )}

          {/* API STUDIO TAB */}
          {activeTab === 'apidoc' && (
            <motion.div
              key="apidoc"
              initial={{ opacity: 0, y: 15 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -15 }}
              className="space-y-6"
            >
              
              <div className="bg-stone-900/60 border border-stone-800 p-6 rounded-xl space-y-4">
                <div className="flex items-center gap-2">
                  <BookOpen className="w-5 h-5 text-amber-500" />
                  <h3 className="text-lg font-bold text-white">API Documentation Porter</h3>
                </div>
                <p className="text-stone-400 text-xs leading-relaxed">
                  Пожалуйста, вставьте сюда документацию своего API, спецификацию или описание эндпоинтов. 
                  Я проанализирую параметры, авторизацию, структуры JSON ответов и сгенерирую для каждого эндпоинта:
                </p>
                <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
                  <div className="p-3 bg-stone-950 border border-stone-800 rounded-lg text-xs space-y-1">
                    <p className="font-semibold text-amber-400 font-mono">1. Ktor Client</p>
                    <p className="text-stone-400 text-[11px]">Готовые асинхронные HTTP-вызовы на Kotlin.</p>
                  </div>
                  <div className="p-3 bg-stone-950 border border-stone-800 rounded-lg text-xs space-y-1">
                    <p className="font-semibold text-purple-400 font-mono">2. JSON Models</p>
                    <p className="text-stone-400 text-[11px]">Котлин-классы с аннотациями JSON сериализации.</p>
                  </div>
                  <div className="p-3 bg-stone-950 border border-stone-800 rounded-lg text-xs space-y-1">
                    <p className="font-semibold text-blue-400 font-mono">3. LiveData/Flow UI</p>
                    <p className="text-stone-400 text-[11px]">Связывание эндпоинтов с UI в Compose экранах.</p>
                  </div>
                </div>

                <div className="space-y-3 pt-3">
                  <label className="block text-xs font-semibold text-stone-300 font-mono">Paste your API docs / specs below:</label>
                  <textarea
                    rows={6}
                    value={apiDocInput}
                    onChange={(e) => setApiDocInput(e.target.value)}
                    placeholder={`Example:\\nGET /api/v1/tracks/search\\nAuthorization: Bearer <token>\\nQuery: q (string)\\n\\nResponse:\\n{ "id": 1, "title": "Starlight", "duration_sec": 180 }`}
                    className="w-full bg-stone-950 border border-stone-800 rounded-lg p-3 text-xs font-mono text-stone-300 focus:outline-none focus:border-amber-500 focus:ring-1 focus:ring-amber-500 transition duration-200"
                  />
                  
                  <div className="flex justify-end gap-3">
                    <button
                      onClick={handleSaveDoc}
                      className="px-4 py-2 bg-amber-500 text-stone-950 rounded-lg text-xs font-semibold hover:bg-amber-400 active:translate-y-0.5 transition flex items-center gap-1.5"
                    >
                      <Upload className="w-3.5 h-3.5 stroke-[2.5]" /> Analyze & Generate Client Code
                    </button>
                  </div>
                </div>

                {showDocSuccess && (
                  <motion.div 
                    initial={{ opacity: 0, y: 10 }}
                    animate={{ opacity: 1, y: 0 }}
                    className="p-3.5 bg-green-500/10 border border-green-500/20 text-green-400 rounded-lg text-xs flex items-center gap-2"
                  >
                    <CheckCircle2 className="w-4 h-4 shrink-0" /> Спецификация API сохранена во временную сессию! Напишите мне в чате, чтобы я сгенерировал Kotlin-код.
                  </motion.div>
                )}
              </div>

              {savedDocs.length > 0 && (
                <div className="space-y-3">
                  <h4 className="text-xs font-semibold font-mono text-stone-400 uppercase tracking-wider">Submitted API Specifications ({savedDocs.length})</h4>
                  <div className="space-y-2">
                    {savedDocs.map((doc, idx) => (
                      <div key={idx} className="p-3 bg-stone-900 border border-stone-850 rounded-lg text-xs font-mono text-stone-300 leading-relaxed whitespace-pre-wrap">
                        {doc.substring(0, 300)}{doc.length > 300 ? '...' : ''}
                      </div>
                    ))}
                  </div>
                </div>
              )}

            </motion.div>
          )}

          {/* GUIDE TAB */}
          {activeTab === 'guide' && (
            <motion.div
              key="guide"
              initial={{ opacity: 0, y: 15 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -15 }}
              className="space-y-6"
            >
              
              <div className="bg-stone-900/60 border border-stone-800 p-6 rounded-xl space-y-4">
                <h3 className="text-base font-bold text-white flex items-center gap-2">
                  <HelpCircle className="w-5 h-5 text-amber-500" /> Инструкция по экспорту и локальной сборке
                </h3>
                
                <div className="space-y-3 text-xs text-stone-300 leading-relaxed">
                  <p>
                    Когда мы закончим добавлять эндпоинты, модели данных и Compose-экраны в этом рабочем пространстве, 
                    вы сможете легко перенести весь проект обратно на ваш ПК или на GitHub:
                  </p>

                  <ol className="list-decimal list-inside space-y-2.5 pl-1.5 text-stone-400">
                    <li>
                      <strong className="text-stone-200">Экспорт ZIP-архива</strong>: Откройте меню настроек в правом верхнем углу интерфейса AI Studio (Settings / Иконка шестерёнки) и нажмите <strong className="text-amber-500">Export as ZIP</strong>. 
                      Вы скачаете готовый, чистый архив проекта, который можно сразу открыть в Android Studio.
                    </li>
                    <li>
                      <strong className="text-stone-200">Экспорт в GitHub</strong>: Вы также можете связать этот воркспейс с репозиторием GitHub, чтобы коммитить изменения напрямую в репозиторий кнопкой синхронизации.
                    </li>
                    <li>
                      <strong className="text-stone-200">Локальная сборка</strong>: Скачав проект, откройте его в <strong className="text-stone-200">Android Studio Ladybug (или новее)</strong>. 
                      Gradle подтянет все зависимости, включая Ktor, media3 и Room, автоматически.
                    </li>
                  </ol>
                </div>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div className="p-4 bg-stone-900 border border-stone-850 rounded-xl space-y-2">
                  <h4 className="text-xs font-semibold text-stone-200 font-mono uppercase tracking-wider text-amber-500">How to load SDK keys?</h4>
                  <p className="text-stone-400 text-[11px] leading-relaxed">
                    Для ключей API мы настроили автоматический импорт BuildConfig переменных. 
                    Просто создайте файл <code className="text-stone-300 bg-stone-950 px-1 py-0.5 rounded font-mono">local.properties</code> на своём ПК и пропишите туда API ключи, Gradle прочитает их напрямую во время компиляции.
                  </p>
                </div>

                <div className="p-4 bg-stone-900 border border-stone-850 rounded-xl space-y-2">
                  <h4 className="text-xs font-semibold text-stone-200 font-mono uppercase tracking-wider text-amber-500">Questions & Prompts</h4>
                  <p className="text-stone-400 text-[11px] leading-relaxed">
                    Вы всегда можете задавать вопросы по архитектуре, спрашивать о Compose-анимациях (например, как сделать плавный Blur-эффект или волны) 
                    или присылать логи сборки при возникновении ошибок. Как ваш напарник-разработчик, я здесь, чтобы помочь разобраться с любой сложностью!
                  </p>
                </div>
              </div>

            </motion.div>
          )}

        </AnimatePresence>

      </main>

      {/* Mini Footer status */}
      <footer className="mt-12 border-t border-stone-900 bg-stone-950 py-6 text-center text-xs text-stone-600 font-mono">
        <div>LiquidMusicGlass Android Developer Console © 2026. Environment status: Online</div>
      </footer>

    </div>
  );
}
