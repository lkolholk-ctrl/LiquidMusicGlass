# Обучение AutoMix v3 на Kaggle

`automix_v3_kaggle.ipynb` — полный автономный пайплайн: скачивает MTG-Jamendo
(~15 000 полных CC-треков, сбалансированных по 9 жанровым группам), считает
фичи **ровно как приложение** (порт `MelSpectrogram.kt` / `FeatureExtractor.kt`
1:1), размечает ~30 000 пар портированным алгоритмом-учителем
(`BPMDetector` / `KeyDetector` / `EnergyAnalyzer` / `SmartTransitionFinder`),
обучает клон текущей архитектуры (сиамский CNN + 5 голов) и экспортирует
`automix_v2.tflite` (fp16) — drop-in замену файла в `app/src/main/assets/`.

## Запуск (с телефона)

1. kaggle.com → **Create → New Notebook**
2. **File → Import Notebook** → вкладка GitHub → этот репозиторий →
   `ml/automix_v3_kaggle.ipynb` (для приватного репо нужно связать GitHub-аккаунт
   с Kaggle). Запасной путь: открыть файл на GitHub → Raw → сохранить → Upload.
3. Панель **Settings** справа: **Internet → On**, **Accelerator → GPU** (T4/P100).
4. **Save Version → Save & Run All (Commit)** — и можно закрывать вкладку.
   Полный прогон ~6–8 часов (лимит сессии 9–12 ч).
5. Результаты — во вкладке **Output** у сохранённой версии:
   `automix_v2.tflite`, `report.json`, `confusion_matrix.png`, `dataset_stats.json`.

## Контракт с приложением (не ломать!)

- входы: `mel_a`/`mel_b` `[1,431,128,1]` (22050 Гц, FFT 2048, hop 512, 128 мел,
  dB от глобального максимума, `(dB+80)/80`), `aux` `[1,32]`
  (BPM/200 + 12 хром + RMS + центроид + onset, для A и B);
- выходы в порядке суффиксов `:N`: 0 compatibility, 1 crossfade_duration,
  2 entry_offset, 3 transition_type `[1,6]`, 4 transition_start —
  `MLTransitionPredictor` матчит их по имени, по суффиксу и по форме `[1,6]`.

Датасет MTG-Jamendo — только для некоммерческого исследовательского
использования (для коммерческого нужна лицензия Jamendo).
