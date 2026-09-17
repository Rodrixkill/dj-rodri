# DJ Rodri: plan de diseño (v2, centrado en el análisis de playlists)

## Contexto
DJ Rodri es una app Android personal para fiestas (tú y hasta 4 familiares), solo para el Xiaomi 17 Ultra con Android 16. El valor principal es **analizar tus playlists** para saber de cada canción dónde están el coro, el drop y el outro, y qué canciones combinan entre sí. Con eso, al darle play, el DJ **decide solo cuándo cortar en un punto natural** y cuándo dejar la canción completa, sin sobre-mezclar. Spotify solo reproduce, salta y adelanta: la app **no toca el audio**.

- Repo nuevo en `C:\dj-rodri`, con Kotlin y Compose nativos. De Juegos Fiesta se toman las convenciones: registro de extensiones, lógica pura con tests y *fakes*, IDs en vez de copias, estilo de commits e interfaz en español.
- Antes de crear archivos o instalar dependencias, se pide confirmación.

## Decisiones tomadas
| Tema | Decisión |
|---|---|
| Stack | Kotlin 2.2+, Compose con Material 3, Gradle con Kotlin DSL y version catalog, Hilt, Room, DataStore y Coroutines/Flow. SDK 36 para todo y solo arm64. |
| Fuente de playlists | Web API de Spotify, **a pedido**: eliges una playlist (propia, colaborativa o "Tus me gusta") y se leen sus canciones. Guardadas solo como id local ↔ URI e ISRC. |
| Análisis | **Sin audio de Spotify y sin IA.** Fuentes abiertas: LRCLIB (letras sincronizadas → coros), AcousticBrainz, ReccoBeats y GetSongBPM (BPM, tonalidad, energía), más **tus toques** ("¡drop!") y reglas propias. Nunca se graba ni captura audio. |
| Transiciones | **Cortes naturales:** A termina en un límite de frase (fin de coro o inicio del outro) → `skipNext` → **B arranca desde su inicio**, salvo en las "entradas en el drop" (ver Motor DJ). Si el análisis no es confiable, la canción suena **completa**. |
| Naturalidad | Tope de cortes con reglas anti sobre-mezcla (ver motor). Botón "Completa" para dejar entera la canción actual. |
| Controles de fiesta | Play/pausa, siguiente, anterior, cambiar de playlist, "Completa", "No va" (no me gusta) y energía ↑/↓, desde la app, la notificación, el widget y el tile. |
| IA local | El LLM (LiteRT-LM) traduce tu pedido a números y nunca ve tus canciones; un intérprete por palabras clave es el respaldo. Ver `docs/ia-local.md`. |
| Spotify | App Remote 0.8.0 (AAR, sin mantenimiento) para controlar y auth 5.0.0 con PKCE. Endpoints de reproductor de la Web API como respaldo. |

## Verificación de Spotify (17-sep-2026)
- **Política, sección III:** prohíbe control por voz (III.3), mezclar, encadenar o superponer (III.7), imitar la experiencia central (III.11) e ingerir contenido de Spotify en IA (III.14).
  - Con este diseño, el núcleo **no usa IA ni modifica el audio**. Los cortes son `skipNext` en momentos elegidos. Queda un gris menor por automatizar transiciones "tipo DJ".
- **Modo desarrollo:** dueño con Premium, máximo 5 usuarios y 25 client IDs con la cuota compartida (jul-2026). Los 429 traen `QUOTA_EXCEEDED`.
- **Endpoints que se usan:**
  - `GET /me/playlists`.
  - `GET /playlists/{id}/items`, que solo trae canciones de playlists propias o colaborativas: las ajenas hay que copiarlas a una propia.
  - `GET /me/tracks` (tus me gusta).
  - `GET /tracks/{id}` para `external_ids.isrc`, uno por uno porque ya no hay consultas por lotes.
  - Reproductor y cola.
- **No existen:** Audio Analysis/Features ni recomendaciones. La búsqueda llega a 10 resultados.
- **El crossfade de Spotify no se aplica al saltar**, solo cuando la canción termina sola. Por eso los cortes son secos, en un punto natural de la frase.

---

## Arquitectura
```
UI fiesta (Compose) · notificación · widget Glance · tile
        │ ViewModels / StateFlow
Casos de uso: AnalyzePlaylist, StartParty, Next, Previous, SwitchPlaylist, KeepFull, Dislike, EnergyUp/Down
        │
Dominio puro (JVM): TrackProfile, CuePoints, Compatibility (BPM/Camelot/energía), SetBuilder, CutDecider, VirtualQueue, SkipMetrics
        │ puertos
Adaptadores: SpotifyAuth · SpotifyRemote (App Remote) · SpotifyWebApi · Sources (LRCLIB, AcousticBrainz, ReccoBeats, GetSongBPM) · Room
```

### 1. Análisis de playlist (`analysis/`)
1. Eliges la playlist, se leen sus canciones y se obtiene el ISRC de cada una. Todo en segundo plano con WorkManager, con barra de progreso y reanudable.
2. Por cada canción se arma su **`TrackProfile`**:
   - BPM, tonalidad y energía (AcousticBrainz, ReccoBeats o GetSongBPM).
   - **Coros:** `CuePointFinder` busca el bloque de líneas repetido en el LRC de LRCLIB → inicio y fin de cada coro.
   - **Outro:** última línea de letra y duración.
   - **Drop estimado:** inicio del primer coro, o los compases 17 o 33 según el BPM. Se reemplaza por **tu toque** cuando lo marcas.
   - **Puntos de corte candidatos:** fin del 2.º coro, inicio del outro y fin de frase según el BPM. Cada uno con su **confianza** (0–1).
3. **Compatibilidad entre pares:** BPM dentro de ±6 % (o mitad/doble), tonalidad vecina en la rueda Camelot y energía de salida de A ≈ energía de entrada de B. Se guarda en `compat_pair` con un puntaje.
4. **Modo "marcar drops":** mientras escuchas, un botón grande "¡Drop!" guarda la posición de PlayerState. Es dato tuyo y es el más exacto.
5. **Pantalla de resultado:** "Playlist Perreo: 142 canciones, 118 con coros detectados, 96 con BPM, 23 sonarán completas".

Todo se guarda en Room: `track`, `spotify_ref`, `track_profile`, `cue_point`, `compat_pair`, `playlist`, `analysis_run`, `play_event` y `user_mark`. Solo se guardan IDs y datos derivados, sin respuestas crudas.

### 2. Motor DJ (`dj/`)
- **`SetBuilder`** ordena la playlist analizada: arranca por una canción adecuada, encadena pares compatibles, sigue una curva de energía suave (subidas y respiros) y evita repetir artista seguido.
- **`CutDecider`** decide, **por canción**, si suena completa o se corta, y dónde:
  - **Completa** si: no hay punto de corte con confianza ≥ 0,7, dura menos de ~3:00, la marcaste favorita o "Completa", o la anterior ya se cortó.
  - **Se corta** si: tiene un outro largo o repite el coro 3 veces o más, o la energía cae y la siguiente la levanta.
  - **Presupuesto de naturalidad:** como máximo ~40 % de canciones cortadas por sesión, **nunca dos cortes seguidos**, y nunca antes del primer coro completo. Ajustable en Ajustes (Natural / Fiesta / Hora loca).
- **Entrada en el drop (lo que da la sensación de DJ):**
  - En momentos elegidos, en vez de que B arranque desde 0, el DJ corta A en un fin de frase y **B entra directo en su drop o primer coro** (`skipNext` + `seekTo`).
  - Se usa con moderación: solo cuando B tiene un drop confiable (tu marca "¡Drop!" o un coro con confianza ≥ 0,8) y la energía sube.
  - Como máximo ~1 de cada 4 transiciones, nunca dos seguidas y nunca en la primera canción del set.
  - Variante "con pausa corta" si el spike muestra que la entrada directa deja oír el inicio de B.
  - Los modos de naturalidad regulan la frecuencia: Natural casi nunca, Fiesta a veces, Hora loca seguido.
- **Ejecución:** la `VirtualQueue` mantiene 1–2 canciones encoladas en Spotify. Cuando la posición llega al punto de corte, se hace `skipNext()` y B arranca desde 0. Si la canción va completa, termina sola y el crossfade de Spotify actúa.
- **Controles:**
  - **Siguiente:** salta y rearma el orden desde ahí.
  - **Anterior:** vuelve a la canción previa del historial propio con `play(uri)`.
  - **Cambiar playlist:** si está analizada, arma un set nuevo y entra con un corte natural. Si no, suena normal mientras se analiza en segundo plano.
  - **Completa:** cancela el corte de la actual.
  - **No va:** salta y baja su puntaje.
  - **Energía ↑/↓:** reordena lo que queda.
- **Aprendizaje:** se registra cada evento (completa, cortada por el DJ, saltada por ti antes de 30 s, "no va"). Métrica clave: **saltos manuales por sesión** (si saltas mucho, el DJ eligió mal).

### 3. Experiencia de fiesta
- **Pantalla principal "Fiesta":**
  - Carátula grande y la próxima canción.
  - Indicador de lo que viene: "completa" o "corte en 2:41".
  - Botones enormes: ⏮ ⏯ ⏭, Completa, No va, 🔥/😌.
  - Selector de playlist a un toque.
  - Pantalla siempre encendida opcional.
- **Notificación y servicio en primer plano** durante la fiesta, con los mismos controles, para que HyperOS no mate la app.
- **Widget y tile de Ajustes rápidos:** "Fiesta ON/OFF", siguiente y cambiar playlist.
- **Onboarding HyperOS:** inicio automático, batería sin restricción, bloquear en recientes y activar crossfade en Spotify (se lee con `getCrossfadeState()` y se avisa).

## Estructura de carpetas (`C:\dj-rodri`)
```
dj-rodri/
├─ app/                      # Application, MainActivity, navegación, DI
├─ build-logic/convention/   # plugins Gradle compartidos
├─ gradle/libs.versions.toml
├─ core/
│  ├─ domain/                # JVM puro: TrackProfile, CuePoints, Compatibility, SetBuilder, CutDecider, VirtualQueue, métricas
│  ├─ database/              # Room + migraciones versionadas
│  ├─ designsystem/          # tokens de tema y componentes grandes de fiesta
│  └─ testing/               # fakes (FakeSpotifyRemote con reloj simulado), playlists sintéticas, LRC de ejemplo
├─ spotify/
│  ├─ auth/                  # PKCE, tokens cifrados con Keystore
│  ├─ remote/                # wrapper App Remote → Flow<PlayerState>, reconexión
│  └─ webapi/                # playlists, items, ISRC; 429/QUOTA_EXCEEDED
├─ sources/                  # clientes LRCLIB, AcousticBrainz (volcado importado), ReccoBeats, GetSongBPM
├─ analysis/                 # AnalyzePlaylistWorker, CuePointFinder, perfilador, compatibilidad
├─ dj/                       # motor en ejecución: reloj de posición, ejecución de cortes, controles
├─ feature/
│  ├─ onboarding/  ├─ playlists/ (elegir/analizar/resultado)  ├─ party/ (pantalla fiesta)  └─ settings/
├─ surfaces/                 # servicio en primer plano, notificación, widget Glance, tile
├─ ai/  voice/  context/     # FUTURO opcional
├─ localdj/                  # FUTURO: mezcla real solo con archivos propios
└─ docs/                     # DESIGN.md, adr/0001-spotify-policy.md
```
Secretos: `SPOTIFY_CLIENT_ID` en `local.properties` (en `.gitignore`) → `BuildConfig`. Repo de GitHub privado.

---

## Fases (cada una se prueba en tu celular)
### Fase 0: Spike de validación (1–2 días)
1. ¿Funcionan la autenticación 5.0.0 con PKCE (`djrodri://callback`) y App Remote 0.8.0 con la versión actual de Spotify en HyperOS 3? ¿Llega el PlayerState con la canción (problema #386)?
2. **Precisión del corte:** cada cuánto llega la posición, cuánto tarda `skipNext()` en sonar, y si con un reloj propio se logra un error < 300 ms.
3. ¿`queue(uri)` funciona? ¿`skipPrevious` y `play(uri)` sirven para "anterior"? ¿Qué pasa con Spotify en segundo plano y la pantalla bloqueada?
4. `GET /playlists/{id}/items` sobre una playlist tuya y sobre "Tus me gusta". ¿Qué % tiene ISRC?
5. Sobre 30 canciones tuyas: ¿qué % tiene letra sincronizada en LRCLIB y BPM en alguna fuente?

**Entregable:** APK de prueba (login, elegir playlist, play, un corte programado a un segundo dado) más un informe de sí o no con números. **Si App Remote falla, se rediseña con la Web API antes de seguir.**

### Fase 1: Login, playlists y control
- **Qué se hace:** login, lista de tus playlists, play de una playlist con la cola virtual, y controles siguiente/anterior/pausa/cambiar playlist en pantalla y notificación. Servicio en primer plano y onboarding HyperOS.
- **Entregable:** manejar la música de una reunión solo desde DJ Rodri, con la pantalla bloqueada.

### Fase 2: Análisis de playlists (el núcleo)
- **Qué se hace:** worker de análisis, fuentes abiertas, `CuePointFinder`, perfiles, pares compatibles, modo "¡Drop!" y pantalla de resultado con cobertura.
- **Entregable:** analizar tu playlist de fiesta y ver por canción los coros, el BPM, los cortes candidatos y si "suena completa".

### Fase 3: Motor DJ
- **Qué se hace:** `SetBuilder`, `CutDecider` con presupuesto de naturalidad y modos Natural/Fiesta/Hora loca, ejecución de cortes, Completa, No va, energía ↑/↓ y registro de eventos.
- **Entregable:** una fiesta de prueba de 1 hora: el DJ corta solo en puntos naturales, deja canciones completas y nunca corta dos seguidas. Ves la métrica de saltos manuales.

### Fase 4: Superficies de fiesta
- **Qué se hace:** widget, tile, notificación completa, pantalla siempre encendida y panel de métricas por sesión.
- **Entregable:** controlar la fiesta sin abrir la app.

### Fase 5+ (opcional)
- Pedidos en lenguaje natural con LLM local, solo sobre datos abiertos ("más perreo").
- Voz que arma un plan que se aplica con un toque.
- Contexto (Bluetooth, ruido ambiente).
- `localdj` con archivos propios.

---

## Riesgos
| # | Riesgo | Impacto | Mitigación |
|---|---|---|---|
| 1 | App Remote no funciona con Spotify actual o Android 16 | Crítico | Spike de la fase 0. Respaldo: Web API del reproductor, con más latencia. |
| 2 | Imprecisión del corte (posición o latencia del salto) | Alto | Reloj propio calibrado con PlayerState, compensar la latencia medida y cortar en fin de frase (hay margen de ~1 s). |
| 3 | Poca cobertura de LRCLIB o del BPM en tus canciones | Alto | Sin datos confiables, la canción suena completa. Modo "¡Drop!" y marcas manuales. La cobertura se ve por playlist. |
| 4 | Los cortes secos se sienten bruscos | Medio | Solo en fin de frase, presupuesto de naturalidad y modos ajustables. Medir los saltos manuales. |
| 5 | Política: automatizar transiciones "tipo DJ" (III.7 y III.11) | Medio-bajo | Sin tocar el audio, sin IA en el núcleo, sin distribución, repo privado y uso de la API a pedido. |
| 6 | Playlists ajenas no devuelven canciones | Bajo | Copiarlas a una playlist propia. |
| 7 | HyperOS mata la app en plena fiesta | Medio | Servicio en primer plano, onboarding y verificación de los permisos al iniciar la fiesta. |
| 8 | Lentitud del análisis (1 petición por segundo, ISRC uno por uno) | Bajo | Worker en segundo plano, reanudable, con caché permanente por ISRC: cada canción se analiza una sola vez. |

## Testing
1. **Unitarios, JVM puro, JUnit 5 con nombres en frase:**
   - `CuePointFinder` con LRC reales de ejemplo: coro repetido, puente, sin letra, letras con muletillas.
   - `Compatibility`: rueda Camelot, BPM mitad/doble.
   - `CutDecider` con tests de propiedades (Kotest): **nunca dos cortes seguidos**, nunca más del presupuesto, nunca antes del primer coro y **completa si la confianza < umbral**.
   - `SetBuilder`: la curva de energía no tiene saltos grandes y no se repite artista.
   - `VirtualQueue`: siguiente, anterior y cambio de playlist.
2. **Integración:**
   - Room en memoria (migraciones e integridad).
   - Clientes de fuentes y de la Web API con MockWebServer y respuestas grabadas (incluye 429 `QUOTA_EXCEEDED`).
   - `FakeSpotifyRemote` con reloj virtual: simula una fiesta de 2 horas en milisegundos y verifica cuándo y dónde se llamó a `skipNext`.
3. **En el dispositivo:** Compose UI tests, Maestro para los flujos (elegir playlist → analizar → fiesta → siguiente/anterior/Completa) y un test de humo con Spotify real (`@RequiresSpotify`).
4. **Calidad del "DJ":**
   - **Set de referencia:** 30 canciones que marcas tú a mano (coros, drop y "¿dónde cortarías?"). Se mide el error en segundos entre `CuePointFinder` y tus marcas, la precisión de "suena completa" y el % de cortes a menos de 2 s de un punto que tú aceptarías.
   - **En uso real, por sesión:** saltos manuales, "No va", "Completa" pulsados (señal de sobre-mezcla) y % de canciones cortadas. Informe comparable entre versiones del motor.

## Próximos pasos (cada uno con tu confirmación)
1. Crear `C:\dj-rodri`, `git init` (rama `master`) y el esqueleto Gradle del spike.
2. Registrar la app en el panel de Spotify (lo haces tú): paquete `com.rvillarroel.djrodri`, SHA1 de debug y `djrodri://callback`.
3. Descargar el AAR de App Remote 0.8.0 (te digo el tamaño antes).
4. Correr el spike y decidir con los números.
