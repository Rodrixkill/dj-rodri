# Filosofía DJ de DJ Rodri

La fuente es la especificación "DJ Intelligence — Multi-genre mixing & music analysis" que escribió Rodrigo (21-sep-2026). Este documento la traduce a lo que la app **puede hacer de verdad**. Con Spotify la app nunca toca el audio: solo **elige, corta, salta al drop y deja que el crossfade de Spotify funda**. Todo lo que exige procesar el audio queda para el módulo `localdj`, con archivos propios.

## Principios que la app sigue

1. **El BPM nunca es la única regla.** Cada cambio se puntúa con tempo, estilo, energía, tonalidad y ánimo (`Compatibility.match`). Medio tempo y doble tempo cuentan como el mismo pulso: 90 ↔ 180 y 87 ↔ 174.
2. **Compatible técnicamente no es lo mismo que que suene bien.** Por eso el estilo y el ánimo pesan tanto como el tempo.
3. **La dificultad no es una nota.** Cada cambio es *Suave*, *Moderado*, *Creativo* o *Difícil*, y la pantalla de fiesta explica por qué: "Moderada · reggaetón → hip hop, estilos cercanos".
4. **El movimiento sigue al estilo.** No todo cambio es una mezcla larga (ver la tabla de abajo).
5. **Open format permitido.** Ningún cambio de género está prohibido. Un cambio lejano solo baja la afinidad y se vuelve un corte limpio en vez de una mezcla.
6. **Las voces mandan.** Nunca se funden dos canciones con mucha voz, y los cortes caen al final de una frase cantada o en un tramo instrumental.

## Movimientos posibles con Spotify

| Movimiento | Cómo se hace | Para qué estilos |
|---|---|---|
| Completa | La canción termina y funde sola con el crossfade de Spotify | Todos. Rock, tropical y baladas lo usan más |
| Corte en frase | Corte seco al final del coro o en un tramo instrumental | Reggaetón, pop, hip-hop, rock, tropical |
| Entrada en el drop | Tras un corte, la siguiente entra en su coro o drop | Pop (de gancho a gancho), reggaetón, EDM |
| Drop swap | La canción sube hacia su 2.º drop y en ese instante cae el drop de la siguiente | EDM, electrónica, reggaetón, hip-hop, dancehall |
| Fundido largo | Se encola la siguiente y la canción salta a sus últimos segundos: Spotify funde | House, techno, trance, disco, funk |
| Otra vez (pull up) | La canción vuelve al drop que acaba de sonar | Botón manual, típico de reggaetón y dancehall |

## Familias de estilo (`StyleFamily`)

| Familia | Géneros | Drop swap | Fundido largo | Cuánto corta |
|---|---|---|---|---|
| Electrónica | house, techno, trance, electronic | sí (el doble de seguido, basta la rejilla del BPM) | sí | 1,2× |
| EDM | edm, dubstep, drum & bass | sí | no | 1,2× |
| Reggaetón | reggaeton | sí | no | 1× |
| Hip hop | hip hop, trap | sí | no | 1× |
| Afro y dancehall | afrobeats, amapiano, dancehall, reggae | sí | no | 1× |
| Pop | pop, latin | no (va de gancho a gancho) | no | 0,9× |
| Funk y disco | disco, funk, soul, r&b | no | sí | 0,9× |
| Tropical | salsa, bachata, merengue, cumbia | no | no | 0,5× |
| Rock | rock, metal, punk, indie | no | no | 0,5× |
| Tranqui | balada, folk, jazz, blues, country, clásica | no | no | 0,4× |

Afinidad entre familias (0 a 1):
- **Altas:** reggaetón ↔ hip hop, reggaetón ↔ afro/dancehall, electrónica ↔ EDM, disco ↔ house.
- **Bajas:** rock ↔ reggaetón, rock ↔ tropical, tranqui ↔ EDM.

La tabla completa está en `StyleFamily.AFFINITY`.

## Qué parte de la especificación está implementada y dónde

| Sección de la especificación | Estado | Dónde |
|---|---|---|
| 1. Compatibilidad técnica y musical | ✅ | `Compatibility.match` |
| 2. DJing no es solo beatmatching | ✅ | Movimientos por familia en `TransitionPlanner` |
| 3–9. Electrónica, house, techno, trance, EDM | ✅ fundido largo, drop swap y frases de 8 compases con la rejilla del BPM · ⏳ EQ, filtros y loops (`localdj`) | `CueEstimator`, `StyleFamily` |
| 10–12. Reggaetón y sus voces | ✅ cortes secos en frase, de gancho a gancho, drop swap, voces · ⏳ eco y filtros (`localdj`) | `VocalAnalyzer`, `TransitionPlanner` |
| 13–17. Latino y tropical | ✅ familia propia, sin drop swap, corta menos · ❌ clave de salsa (necesita el audio) | `StyleFamily` |
| 18–20. Hip hop, R&B y pop | ✅ | `StyleFamily` |
| 21–22. Rock e indie | ✅ sin mezclas forzadas: completa o corte seco al final del coro | `StyleFamily` |
| 23–24. Funk y disco | ✅ fundido largo, disco → house con afinidad alta | `StyleFamily` |
| 25–29. Afrobeats, dancehall, amapiano, drum & bass, dubstep | ✅ géneros nuevos, medio tempo | `Genres`, `StyleFamily` |
| 30–31. Open format y cambios de género | ✅ permitido, explicado en pantalla | `Compatibility` |
| 32–34. Modelo de compatibilidad y dificultad | ✅ | `Compatibility`, `Difficulty` |
| 35. Ánimo | ✅ con la positividad (valence) de ReccoBeats: alegre, neutro, oscuro | `Compatibility.mood` |
| 36. Densidad de voz | ✅ a partir de la letra sincronizada | `VocalAnalyzer` |
| 37. Densidad instrumental | 🟡 solo tramos sin voz, sacados de la letra · ⏳ el resto necesita el audio | `VocalProfile.instrumentals` |
| 38. Densidad de graves | ⏳ necesita el audio (`localdj`) | — |
| 39. Canciones conocidas | ❌ Spotify quitó la popularidad en 2026 · idea: aproximarla con tus me gusta y las veces que sonó en tus fiestas | — |
| 40–41. Recomendación de cuándo, dónde, cómo y por qué | 🟡 el DJ lo ejecuta solo y muestra el porqué · ⏳ "en 16 compases" con la rejilla del BPM | Pantalla de fiesta |
| 42–43. Modo principiante y modo avanzado | 🟡 hoy solo el modo simple (nivel y razones) · ⏳ vista avanzada con Camelot, compases y fases | — |

✅ hecho · 🟡 parcial · ⏳ pendiente · ❌ no se puede sin el audio o sin los datos

## Reglas que no cambian

- Nunca dos cortes seguidos, y los primeros temas de la noche suenan completos (calentamiento).
- La última canción siempre suena completa.
- "Completa" gana siempre, y tus botones (drop swap, fundido ya, otra vez) no gastan el cupo de cortes del DJ.
- Un drop marcado por ti (en la fiesta o en el editor) gana sobre cualquier estimación.
