# 🚀 LiquidMusicGlass: Flagship AutoMix v3 "Apple-Grade" Training Guide

Этот документ — ваш технический план по созданию автомикса, который по качеству сведения не уступает **Apple Music (iOS 17/18)**. 

## 🍏 Секреты "Магии Apple" (Что мы внедряем)
Apple Music не просто накладывает одну песню на другую. Их алгоритм делает три вещи одновременно:
1. **Rhythmic Alignment (BPM Match):** Подгонка темпа входящего трека под текущий.
2. **Frequency Management (The "Bass Swap"):** Полное вырезание низких частот у уходящего трека ровно в тот момент, когда вступает первая "бочка" (kick) нового.
3. **Phrase Matching:** Переход начинается не по таймеру, а в конце музыкальной фразы (например, после 4-го такта припева).

---

## ШАГ 1: Обновленная архитектура нейросети (Флагманский уровень)
Мы добавляем в модель понимание **Энергии** и **Кривых Эквалайзера**.

```python
# [В Colab] Добавьте эти выходы в вашу модель
out_bpm_drift = layers.Dense(1, activation='linear', name='bpm_drift')(dense) # На сколько % ускорить/замедлить трек
out_low_pass = layers.Dense(10, activation='sigmoid', name='low_pass_curve')(dense) # Кривая фильтра НЧ (эквалайзер)
out_energy_match = layers.Dense(1, activation='sigmoid', name='energy_score')(dense) # Общая "мощь" перехода
```

---

## ШАГ 2: Подготовка "Apple-Style" Датасета
Для обучения флагманской модели вам нужно собрать 500-1000 пар треков и разметить их по следующим правилам:

| Параметр | Как размечать для уровня Apple |
| :--- | :--- |
| **Transition Start** | Ищите последний сильный удар (Downbeat) перед финальным затуханием. |
| **Entry Offset** | Пропускайте тишину и первые 1-2 такта, если там нет ритма. Начинайте с первого удара бочки. |
| **EQ Curve** | Уходящий трек должен терять бас (Low) за 2 секунды до конца микса. |
| **BPM Drift** | Рассчитайте разницу: `(BPM_B - BPM_A) / BPM_A`. Модель научится её предсказывать. |

---

## ШАГ 3: Код для Colab (Генерация умных Mel-спектрограмм)
Apple Music видит музыку "глубже". Мы будем подавать модели не просто шум, а разделенный на частоты сигнал.

```python
import librosa
import numpy as np

def extract_apple_grade_features(file_path):
    y, sr = librosa.load(file_path, sr=22050, duration=30.0)
    
    # ФЛАГМАНСКАЯ ФИЧА: Разделение на Гармонику и Перкуссию (HPS)
    # Это позволит модели лучше слышать бит отдельно от вокала
    y_harmonic, y_percussive = librosa.effects.hpss(y)
    
    # Спектрограмма только бита (Percussive)
    mel_percussive = librosa.feature.melspectrogram(y=y_percussive, sr=sr, n_mels=128)
    mel_db = librosa.power_to_db(mel_percussive, ref=np.max)
    
    # Добавляем инфо о тональности (Chromagram)
    chroma = librosa.feature.chroma_cqt(y=y_harmonic, sr=sr)
    
    return mel_db, chroma
```

---

## ШАГ 4: Реализация в Android (Roadmap к Apple Music)

Когда модель обучена и выдает `bpm_drift` и `low_pass_curve`, вот как мы это подключаем в коде:

### 1. Бесшовный BPM Sync (в PlayerController.kt)
```kotlin
// Когда модель говорит: "ускорь на 2%"
val speedFactor = 1.0f + features.bpmDrift 
primaryPlayer.playbackParameters = PlaybackParameters(speedFactor)
```

### 2. Диджейский Эквалайзер (в DJEffectsEngine.kt)
Вместо изменения `player.volume`, мы будем менять параметры `DynamicsProcessing`:
```kotlin
// Apple-style: Bass Cut
// На 50% перехода убираем Low-band на -24dB
val cutoffFreq = curve[step] * 1000 // Динамический срез частот
eq.setPreGainAllChannelsTo(lowBandIndex, -24.0f) 
```

---

## 🏁 Итог: Почему это будет как у Apple?
1. **Слух:** Модель на базе CRNN будет слышать "сетку" бита (через Percussive Mel).
2. **Тактика:** Она не просто "двигает ползунок громкости", а переключает энергию баса между песнями.
3. **Плавность:** Благодаря NPU-ускорению (которое мы уже включили в `ModelLoader.kt`), все эти тяжелые вычисления EQ-кривых будут происходить мгновенно и незаметно для системы.

**Ваша задача сейчас:** Обучить модель на качественном материале (EDM, Pop, Hip-Hop), где четко слышен бит. Чем лучше вы разметите `transition_start`, тем ближе будет эффект к тому самому "магическому" переходу из iOS.
