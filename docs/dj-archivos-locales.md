# DJ con archivos locales (sin Spotify)

La idea es un modo DJ de verdad, con **tus propios archivos de música** (MP3, FLAC, M4A…): dos decks, forma de onda real, beatmatching, ecualizador, efectos, loops y crossfader. Con Spotify esto es imposible: la app nunca recibe su audio, y su política prohíbe mezclarlo, encadenarlo o superponerlo (III.7). Con archivos tuyos no hay restricción.

**Estado:** diseñado y con el núcleo matemático listo y testeado. Falta toda la parte Android. Se implementa después.

---

## 1. Lo que ya existe (JVM puro, con tests)

| Pieza | Archivo | Qué hace |
|---|---|---|
| `BeatGrid` | `core/domain/.../mix/BeatGrid.kt` | Dónde cae cada golpe: `beatAt`, `nearestBeat`, `phraseStartBefore/After` (frases de 8 compases) y `offBeatMs` |
| `BeatTracker` | `core/domain/.../mix/BeatTracker.kt` | Detecta el tempo y el primer golpe a partir de una envolvente de ataques (autocorrelación + filtro de peine). Dice "no estoy seguro" cuando no hay pulso claro. Tolera medio y doble tempo |
| `MixPlanner` | `core/domain/.../mix/MixPlanner.kt` | Planea la transición A→B: dónde empieza a bajar A, dónde entra B (en frase), cuánto dura el fundido, cuánto acelerar B (máximo ±6 %) y curva de volumen de potencia constante. Si no es mezclable, propone corte |
| Filosofía por género | `StyleFamily`, `Compatibility`, `docs/filosofia-dj.md` | Qué movimiento usa cada estilo, compatibilidad por varios factores y explicación. Sirve igual para archivos locales |

## 2. Lo que falta construir

### 2.1 Biblioteca local — `localdj/library`
- **Permiso:** `READ_MEDIA_AUDIO` (Android 13+).
- **Escaneo:** `MediaStore.Audio.Media` → id, título, artista, álbum, duración, ruta/URI, carátula (`MediaStore` album art o la imagen incrustada vía `MediaMetadataRetriever`).
- **Selector de carpeta** opcional (`ACTION_OPEN_DOCUMENT_TREE`) para archivos fuera de la biblioteca del sistema.
- **Tabla Room** `local_track` (uri, título, artista, duración, hash del archivo, fecha de modificación) para reanalizar solo lo que cambió.

### 2.2 Análisis de audio — `localdj/analysis`
Todo en el celular, en segundo plano (WorkManager, como el análisis actual):
1. **Decodificar** con `MediaExtractor` + `MediaCodec` a PCM 16 bits, mono y reducido a 11 025 Hz (basta para ritmo y forma de onda).
2. **Envolvente de ataques (onset):** energía por ventanas de ~10 ms, diferencia positiva (spectral flux simple). Es la entrada de `BeatTracker.estimate(envelope, hopMs)`.
3. **Beat grid:** `BeatTracker` → `BeatGrid` (BPM, primer golpe, confianza).
4. **Forma de onda para mostrar:** picos RMS en unos 1 000–2 000 puntos por canción, en 3 bandas (graves < 250 Hz, medios, agudos) para colorear como el software de DJ. Guardada como blob comprimido.
5. **Tonalidad (opcional, fase 2):** cromagrama con FFT (por ejemplo JTransforms, que es otra dependencia) → perfil de Krumhansl → Camelot.
6. **Estructura:** intro, breakdown, build y drop a partir de la energía por frase de 8 compases (energía de graves que cae = breakdown; que sube de golpe = drop). Da puntos de entrada y salida automáticos (`mixInMs`, `mixOutMs`).
7. **Energía y densidad de graves** por frase, para las reglas de choque de graves de la filosofía.

**Rendimiento estimado:** una canción de 4 min tarda unos 2–4 s en decodificar y analizar en un Snapdragon 8 Elite. 1 000 canciones, alrededor de 1 hora en segundo plano.

### 2.3 Motor de mezcla — `localdj/engine`
- **Dos reproductores** Media3/ExoPlayer (**dependencia nueva: androidx.media3**), uno por deck.
- **Tempo:** `PlaybackParameters(speed)` con cambio de tempo sin cambiar el tono (ExoPlayer usa Sonic y mantiene el pitch).
- **Sincronía (SYNC):** alinear la fase de B con la grilla de A; corregir la deriva cada frase con un ajuste de velocidad mínimo (±0,5 %).
- **Crossfader y volumen:** `MixPlanner.gains(progress)` (potencia constante) aplicado al volumen de cada player.
- **Ecualizador de 3 bandas por deck (LOW/MID/HIGH):** `android.media.audiofx.Equalizer` por sesión de audio de cada player (bandas del sistema), o un `AudioProcessor` propio con filtros biquad (shelf graves < 200 Hz, peak medios, shelf agudos > 4 kHz) para un corte limpio tipo *kill*. Recomendado: `AudioProcessor` propio.
- **Efectos:** filtro pasa-bajos y pasa-altos (biquad con barrido), eco/delay (buffer circular sincronizado al BPM: 1/2, 3/4, 1 beat) y reverb (`PresetReverb` del sistema o un Freeverb simple). Todo como `AudioProcessor` en la cadena de ExoPlayer.
- **Loop:** loop de 1, 2, 4, 8 o 16 beats alineado a la grilla (`seekTo` al inicio del loop al llegar al final, con un fundido de 5 ms para que no haga clic).
- **CUE:** punto de cue por deck (inicio, drop o marcado a mano). Mismo concepto que el drop del editor actual.
- **Automix:** el DJ automático usa `MixPlanner` + las reglas de `StyleFamily` (fundido largo en house, corte en frase en reggaetón, drop swap en EDM), igual que hoy con Spotify pero ahora con mezcla real.
- **Latencia:** usar AAudio de baja latencia (ExoPlayer ya lo hace con `AudioTrack` en modo *performance*). Probar en el Xiaomi que el SYNC se mantenga con menos de 5 ms de desfase.

### 2.4 Interfaz — el Modo DJ que se dejó fuera del rediseño
- Deck A y Deck B: carátula, título, BPM y tono, **forma de onda real en 3 bandas** con cabezal y grilla de beats, CUE, PLAY y SYNC.
- Ecualizador de 3 bandas en sliders verticales, rueda de FX (FILTER, ECHO, REVERB, DELAY), selector de loop "‹ 4 BEAT ›" y crossfader A──●──B.
- **Teléfono:** un deck a la vez, con un deslizamiento para pasar al otro, y el crossfader siempre visible. **Tablet u horizontal:** los dos decks lado a lado (como la referencia `references/design.png`).
- Mismo sistema de diseño (`Dj.colors`, `DjType`, `DjIcons`): rojo para el deck activo y lo seleccionado, neutros para el resto.

### 2.5 Pruebas
- **JVM:**
  - `BeatTracker` con archivos reales cortos: tempo conocido y ±1 BPM.
  - Estructura (drop detectado a menos de 1 compás).
  - `MixPlanner` con grillas reales.
  - Filtros biquad (respuesta en frecuencia).
- **Instrumentadas en el celular:**
  - La deriva de SYNC en 5 minutos es menor a 10 ms.
  - Sin clics en loops.
  - El CPU durante la mezcla se mantiene bajo el 15 %.
- **De oído:** un set de referencia de 20 transiciones tuyas, calificadas como en el laboratorio actual.

## 3. Dependencias nuevas (pedir confirmación antes)
- `androidx.media3:media3-exoplayer`: reproducción, velocidad y cadena de `AudioProcessor`.
- Opcional: una librería de FFT (JTransforms) para la tonalidad. Se puede escribir una FFT propia de unas 80 líneas.

## 4. Orden sugerido
1. Biblioteca local y reproducción simple con un deck: escuchar tus archivos.
2. Análisis: beat grid y forma de onda, guardados.
3. Dos decks con crossfader y SYNC: la primera mezcla real.
4. Ecualizador, filtro y loop.
5. Automix con `MixPlanner` y `StyleFamily`.
6. Eco, reverb y tonalidad.

## 5. Límites que siguen existiendo
- **Nada de esto funciona con canciones de Spotify:** se usan archivos que sean tuyos (comprados, propios o de pools de DJ).
- Separar pistas (voz/instrumental) es posible con un modelo en la NPU, pero es un proyecto aparte y pesado.
