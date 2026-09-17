# IA local

DJ Rodri entiende pedidos escritos en español ("pon algo para perrear pero sin Bad Bunny") con un modelo que corre **en el celular**, sin internet y sin enviar nada a ningún servidor.

## Qué ve y qué no ve el modelo
```
Tu frase + hora + energía actual  ──►  modelo local  ──►  JSON con números
                                                            │
                          (validado y acotado)               ▼
            energía objetivo · curva · bailabilidad · ánimo · BPM · artistas a evitar
                                                            │
                                                            ▼
                     motor determinista  ──►  elige las canciones de tu playlist
```
- **El modelo nunca recibe tus canciones**: ni títulos, ni artistas, ni nada que venga de Spotify. Solo tu frase y dos números del ambiente.
- Por eso tampoco puede inventar canciones: no elige música, solo traduce lo que pediste a números.
- Esto cumple la regla III.14 de Spotify, que prohíbe meter su contenido en modelos de IA.

## Si algo falla, la fiesta sigue
Siempre hay un intérprete por **palabras clave** (perreo, tranqui, despecho, cumbia, electrónica, a fondo, cerrar la noche, "sin \<artista\>"…). Se usa cuando:
- no hay modelo instalado,
- lo apagaste en Ajustes,
- el modelo tarda más de 8 s, devuelve algo inválido o falla.

La pantalla de fiesta dice siempre quién respondió: "IA local (1,4 s)" o "reglas".

Además, la respuesta del modelo se **valida y se acota**: energías fuera de 0–1, BPM imposibles o modos inventados se corrigen antes de tocar la música.

## Instalar el modelo
El modelo **no viene en la app** (pesa GB). Se copia una vez:

1. Descarga un modelo `.litertlm`, por ejemplo **Gemma 4 E2B** o **E4B** de `litert-community` en Hugging Face.
2. Conéctate por USB y cópialo:
   ```bash
   adb push modelo.litertlm /sdcard/Android/data/com.rvillarroel.djrodri/files/models/
   ```
   La ruta exacta está en **Ajustes → IA local**.
3. En Ajustes, "Probar" lanza un pedido real y muestra qué entendió, en qué backend corre (NPU, GPU o CPU) y cuánto tardó.

La app intenta **NPU → GPU → CPU** en ese orden y se queda con el primero que cargue. El modelo se carga en la primera pregunta y se libera al apagar la IA, para no quitarle memoria a la fiesta.

## Qué falta probar en el celular
- Que el backend NPU cargue en el Snapdragon 8 Elite Gen 5 (SM8850); si no, queda en GPU.
- Cuánto tarda la primera carga y cuánto una respuesta (el objetivo es menos de 2 s en fiesta).
- Que Gemma responda en español con JSON válido; si falla seguido, se sube el tiempo límite o se cambia de modelo.

## Géneros y estilos
Cada canción recibe etiquetas de género desde **MusicBrainz**, buscadas por su ISRC (el código de la industria, no un id de Spotify), y de la ficha de su artista, que se cachea porque una playlist tiene cientos de canciones pero decenas de artistas.

- Las etiquetas libres ("progressive house", "latin pop") se normalizan a un vocabulario corto, y cada género arrastra su familia: pedir **electrónica** también trae house y techno; pedir **perreo** trae reggaetón y latin.
- El género pesa más que cualquier otro número al elegir, pero una canción de otro género no queda prohibida: si no hay nada mejor, suena.
- En la pantalla de fiesta salen los géneros que **de verdad** tiene tu playlist, como botones de un toque.

**"Algo estilo Avicii"**, que el modelo también entiende:
1. Si tienes canciones de Avicii en tu playlist, se usan **sus propias canciones** para sacar energía, bailabilidad, ánimo y rango de BPM. Es tu propia biblioteca, así que es lo más fiel.
2. Si no lo tienes, MusicBrainz dice qué géneros toca y se usan esos.
3. Lo que pediste explícitamente ("estilo Avicii pero tranqui") siempre gana sobre lo que sugiere el estilo.

## Voz
El micrófono usa el **reconocimiento del propio celular** (`createOnDeviceSpeechRecognizer`), así que tu voz no sale del teléfono ni necesita internet. Si tu celular no lo tiene instalado, la app te avisa y te dice dónde instalarlo (Ajustes de Android → Idiomas → Reconocimiento de voz).

El flujo es siempre el mismo:
```
🎤 Hablar → texto → IA local o reglas → "Entendí: perreo sin Bad Bunny" → ✅ Dale (tu toque) → suena
```
**Nada llega a Spotify hasta que tocas "Dale".** Por eso el micrófono no convierte la app en un control por voz de Spotify, que es lo que prohíbe la regla III.3. Escribir el pedido sí se aplica al instante, porque escribir y tocar "Pedir" ya es una acción tuya.
