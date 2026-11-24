# NexosPlay

App Android (Kotlin) + servidor opcional (Python): busca y reproduce YouTube sin
cuenta y sin anuncios, descarga video/audio en el equipo o vía servidor propio.

## Funciones (v1.2)

- Reproduce videos aunque YouTube mande video y audio por separado
  (los combina al vuelo; al descargar baja ambos archivos).

- Buscar videos sin cuenta ni API key.
- Reproducir directo, sin anuncios (streams progresivos, no WebView).
- Interfaz oscura estilo YouTube: tarjetas con duración, canal, vistas,
  descripción y lista de “A continuación”.
- Selector de calidad, botón de compartir y modo solo-audio con un toque.
- Arranque rápido: buffer de inicio corto, caché de streams resueltos,
  sin re-resolver al volver o rotar, y memoria de posición por video.
- Descargar video/audio con nombre `Titulo_720p.mp4` a Descargas.
- Motor yt-dlp opcional por HTTP como segunda fuente de descargas.
- Pegar una URL de YouTube abre el reproductor directo.

## De dónde sale cada lógica

| Repo | Qué se reutiliza |
|---|---|
| [NewPipe](https://github.com/TeamNewPipe/NewPipe) | Downloader OkHttp y selección de streams |
| [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) | Búsqueda y streams sin cuenta |
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) | Motor opcional vía `server/` |
| [FreeTube](https://github.com/FreeTubeApp/FreeTube) | Idea privacidad sin cuenta |
| [Invidious](https://github.com/iv-org/invidious) | Idea frontend alternativo |

Tube Lite (hermana WebView) queda como producto aparte; aquí no hace falta
saltar anuncios porque los streams directos nunca los traen.

## Proyecto

```text
app/      APK: MainActivity (buscar) + PlayerActivity (ver/descargar)
server/   backend yt-dlp: GET /health, GET /info?url=..
.github/  CI: pruebas backend + APK debug como artefacto
```

## Servidor

```sh
python3 -m venv server/.venv
server/.venv/bin/pip install -r server/requirements.txt
server/.venv/bin/python server/server.py 8765
```

Pruebas: `server/.venv/bin/python server/test_server.py` (offline por defecto;
`NEXOSPLAY_LIVE=1` añade una prueba real contra YouTube).

En la app: menú ⋮ → Servidor yt-dlp → `http://IP-DE-TU-PC:8765`.
Vacío = solo motor interno.

## App

- Kotlin, minSdk 24, compileSdk 35, AGP 8.7.3, Gradle 8.9, JDK 17.
- El APK se compila en GitHub Actions (este equipo es aarch64 y los
  build-tools son x86_64): push a `main` → artefacto `app-debug`;
  tag `v*` → Release pública con el APK.
- Remoto: `git@github.com:totalavance-gif/Necoatube.git` (rama `main`).

```sh
git push origin main
git tag v1.0.0
git push origin v1.0.0
```

## Roadmap

DASH adaptativo, listas locales, audio en segundo plano, compartir a NexosPlay.
