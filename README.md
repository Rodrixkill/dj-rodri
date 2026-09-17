# DJ Rodri

DJ para fiestas en Android, de uso personal. Analiza tus playlists de Spotify (coros, drops, BPM, tonalidad), ordena las canciones y cambia de tema en momentos naturales para que suene como si hubiera un DJ. **Nunca toca el audio de Spotify**: solo reproduce, salta y adelanta con App Remote.

- Solo Android 16 (Xiaomi 17 Ultra). Kotlin + Jetpack Compose.
- Diseño y decisiones: [`docs/DESIGN.md`](docs/DESIGN.md).
- **Estado:** fases 1–5 escritas y testeadas en la JVM, incluida la IA local ([`docs/ia-local.md`](docs/ia-local.md)). **Falta la prueba en el celular** ([`docs/fase-0-spike.md`](docs/fase-0-spike.md)), que valida App Remote y los tiempos de corte.

## Cómo se usa
1. **Conectar con Spotify** (una vez; la sesión se renueva sola).
2. **Tus playlists** → elige una → **Analizar playlist**. Sigue en segundo plano con una notificación; cada canción se analiza una sola vez.
3. **🎉 ¡A la fiesta!** El DJ decide solo: canciones completas, cortes en fin de coro y entradas directas al drop, según el modo (Natural / Fiesta / Hora loca).
4. Durante la fiesta:
   - **Pídele algo:** escribe o dicta "pon house estilo Avicii sin Bad Bunny" y el DJ reordena lo que viene: entiende géneros, estilos de artista, ánimo y energía. Lo interpreta un modelo local (o palabras clave si no hay modelo), y por voz nada suena hasta que tocas "Dale".
   - **Controles:** ⏮ ⏯ ⏭, **Completa**, **No va**, 🔥/😌, cambiar de playlist.
   - **¡Drop!** guarda dónde entra el drop de verdad, para todas las fiestas siguientes.
   - **Fuera de la app:** los mismos controles están en la notificación, el widget y el tile de Ajustes rápidos.
5. **Ajustes:**
   - modo por defecto, pausa corta antes del drop y adelanto del corte;
   - guía para que HyperOS no cierre la app;
   - historial de fiestas con el % de saltos;
   - IA local: estado del modelo, interruptor y prueba de un pedido;
   - **Laboratorio**, con las pruebas de la fase 0.

## Módulos
| Módulo | Qué tiene | Tests |
|---|---|---|
| `core/domain` | Lógica pura: letras LRC y coros, BPM y Camelot, y el **motor DJ** (`dj/`): puntos de corte y drop, orden del set, cupo de cortes por modo y la sesión con sus controles. | JUnit 5, con propiedades sobre fiestas aleatorias |
| `core/data` | Clientes de LRCLIB, ReccoBeats y MusicBrainz (géneros) con reintentos, **analizador de playlists** con caché, **`PartyEngine`** (ejecuta el motor sobre un `PlayerPort`) y la **capa de IA**: intérprete por reglas y por LLM, con validación y respaldo. | MockWebServer y reproductor simulado con tiempo virtual |
| `app` | Room con migraciones testeadas, sesión de Spotify (PKCE y tokens cifrados en Keystore), App Remote, motor LiteRT-LM para la IA local, voz en el dispositivo, análisis con WorkManager, servicio de fiesta, pantallas, widget, tile y Laboratorio. | Robolectric (base de datos) |

## Configuración local
1. Crea la app en <https://developer.spotify.com/dashboard>:
   - Android package: `com.rvillarroel.djrodri`
   - SHA1 (keystore de debug): `F3:7C:23:81:47:3C:AE:69:FF:21:1A:E5:25:CF:13:64:5E:36:B3:3F`
   - Redirect URI: `djrodri://callback`
   - APIs: Web API y Android.
2. Pon el Client ID en `local.properties`, que no se versiona:
   ```properties
   SPOTIFY_CLIENT_ID=tu_client_id
   ```
3. Compila e instala con el celular conectado por USB:
   ```bash
   ./gradlew :app:installDebug
   ```

## Tests
```bash
./gradlew :core:domain:test :core:data:test :app:testDebugUnitTest :app:lintDebug
```
