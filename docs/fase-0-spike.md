# Fase 0: spike de validación

Las pruebas están en la app, en **Ajustes → Laboratorio**. Hay que responder en tu celular si Spotify se puede controlar con la precisión necesaria y si tus canciones tienen datos para analizarlas. Cada prueba deja números en el log y el botón **Guardar informe** los junta.

## Antes de empezar
- Spotify instalado y con sesión iniciada (Premium).
- La app registrada en el panel de Spotify y el Client ID en `local.properties` (ver README).
- **Recomendado:** activa el crossfade en Spotify (Ajustes → Reproducción) en 6–8 s.
- **HyperOS:** para la app DJ Rodri (spike), activa el inicio automático y pon la batería en "Sin restricciones".

## Guion (unos 20 minutos)
| # | Paso | Qué responde |
|---|---|---|
| 1 | **Iniciar sesión** | ¿Funciona auth 5.0.0 con PKCE en Android 16? ¿Aparecen tus playlists? |
| 2 | **Conectar App Remote** y reproducir una playlist | ¿Conecta? ¿El log muestra "♪ canción" o "PlayerState SIN canción" (problema #386)? |
| 3 | **Leer crossfade** | ¿App Remote informa el crossfade? |
| 4 | **Medir deriva de posición** con una canción sonando | ¿Qué tan exacta es la posición que calculamos entre eventos? |
| 5 | **Corte natural en X s**, 5 veces | Error del corte y latencia del salto (p50/p95). |
| 6 | Pon en "drop s" el segundo del drop de la **siguiente** canción → **Siguiente directo al drop**, y califica | ¿Se oye el inicio de la canción antes del salto? |
| 7 | Lo mismo con **…con pausa corta**, y califica | ¿Suena más "DJ" con un silencio corto? |
| 8 | **Saltar al final (¿crossfade?)**, y califica | ¿Adelantar al final hace que Spotify use su crossfade? |
| 9 | **⏮ ⏭** y **▶ / +cola** en una canción | ¿Funcionan anterior, reproducir una canción y encolar? |
| 10 | **Siguiente vía Web API** | ¿Sirve el respaldo si App Remote falla, y con qué latencia? |
| 11 | Elige tu playlist de fiesta → **Analizar cobertura (30)** | % de canciones con ISRC, letra sincronizada, coro detectado, BPM y tonalidad. |
| 12 | Bloquea la pantalla 2 min con música → vuelve → **⏭** | ¿Sigue conectado con la pantalla bloqueada? |
| 14 | Haz una fiesta de prueba de 30 min con la app completa, en modo Fiesta | ¿Los cortes caen en buen momento? ¿Cuántas veces saltaste tú? (Ajustes → Tus datos) |
| 13 | **Guardar informe** y ejecuta en el PC el `adb pull` que aparece | El informe llega a `spike-reports/`. |

## Cómo decidimos con los números
| Resultado | Decisión |
|---|---|
| App Remote no conecta o no envía la canción | Se rediseña el control con la Web API (paso 10) antes de seguir. |
| Latencia de salto p95 < 500 ms y deriva p95 < 300 ms | Los cortes en fin de frase son viables tal como están diseñados. |
| La entrada directa al drop se nota y con pausa suena bien | El modo drop usa la pausa corta. |
| Cobertura de coros < 40 % en tu playlist de fiesta | Se prioriza el modo "¡Drop!" (tus marcas) y las canciones sin datos suenan completas. |
