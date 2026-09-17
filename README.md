# DJ Rodri

DJ para fiestas en Android, de uso personal. Analiza tus playlists de Spotify (coros, drops, BPM, tonalidad), ordena las canciones y cambia de tema en momentos naturales para que suene como si hubiera un DJ. **Nunca toca el audio de Spotify**: solo reproduce, salta y adelanta con App Remote.

- Solo Android 16 (Xiaomi 17 Ultra). Kotlin + Jetpack Compose.
- Diseño y decisiones: [`docs/DESIGN.md`](docs/DESIGN.md). Estado actual: **fase 0 (spike de validación)**, ver [`docs/fase-0-spike.md`](docs/fase-0-spike.md).

## Módulos
| Módulo | Qué tiene |
|---|---|
| `core/domain` | Lógica pura en la JVM, sin Android: lectura de letras LRC, detección de coros, compatibilidad de BPM y tonalidad (Camelot), reloj de posición y estadísticas de latencia. En `dj/` está el **motor DJ**: puntos de corte y drop, orden del set, decisión de corte o canción completa, entrada en el drop con límites por modo, y la sesión con siguiente, anterior, Completa, No va, energía y cambio de playlist. |
| `app` | App del spike: login, App Remote, pruebas de tiempo y cobertura, e informe. |

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
./gradlew :core:domain:test
```
