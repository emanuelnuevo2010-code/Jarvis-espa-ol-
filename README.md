# Jarvis — app Android

Asistente por voz que responde cada vez que dices "Jarvis" y sigue escuchando
en segundo plano gracias a un servicio en primer plano (notificación fija).

## Cómo sacar la APK

### Opción A — sin instalar nada (GitHub)
1. Crea un repositorio en github.com y sube todo el contenido de esta carpeta.
2. Entra en la pestaña **Actions** → **Build APK** → **Run workflow**.
3. Al terminar (unos 5 min) abre la ejecución y descarga el archivo **Jarvis-apk**
   (viene en un .zip con `app-debug.apk` adentro).
4. Pásalo al teléfono, ábrelo y permite "instalar apps de fuentes desconocidas".

### Opción B — Android Studio
1. Instala Android Studio y usa **File → Open** sobre esta carpeta.
2. Espera a que termine el "Gradle sync" (descarga lo necesario solo).
3. **Build → Build APK(s)**. La APK queda en `app/build/outputs/apk/debug/app-debug.apk`.
   También puedes conectar el teléfono por USB y darle a **Run**.

## Primer uso
1. Abre Jarvis → ⚙ → elige el cerebro y pega tu clave (Groq es gratis: console.groq.com/keys).
2. Toca **ACTIVAR** y acepta los permisos de micrófono y notificaciones.
3. Di "Jarvis, ¿qué hora es?". Puedes salir de la app o apagar la pantalla.
4. Para parar: botón **Detener** de la notificación, o el botón DETENER en la app.

## Novedades de la versión 1.1
- **Personalidad tipo J.A.R.V.I.S.**: trato de mayordomo elegante, humor seco, saludo al activarse según la hora y respuestas con más carácter. En ⚙ puedes cambiar cómo te llama (por defecto "señor").
- **Burbuja flotante**: al salir de la app, un reactor de arco queda arriba sobre cualquier otra app. Cambia de color según lo que hace (escuchando, pensando, hablando), muestra lo que dice, se arrastra y al tocarla vuelve a Jarvis.
- **Abrir apps por voz**: "Jarvis, abre WhatsApp", "abre la cámara", "ábreme Instagram". Busca entre las apps instaladas por nombre (entiende "wasap", "insta", "ajustes"…).
- **Canciones por voz**: "Jarvis, pon Blinding Lights", "pon música de Bad Bunny en Spotify", "reproduce X en YouTube". Usa Spotify, YouTube Music o YouTube (la primera instalada, o la que pidas).
- **Control de la música**: "pausa", "continúa", "siguiente", "anterior", "sube/baja el volumen".

### Permiso nuevo: "Mostrar sobre otras apps"
La primera vez que actives a Jarvis te lo pedirá (o en ⚙ → Burbuja flotante). Sin este permiso no hay burbuja y Android **no deja** que una app abra otras desde el fondo. Si lo niegas, Jarvis sigue funcionando pero solo abrirá apps y canciones con su pantalla abierta.

### Cosas a tener en cuenta
- Spotify y YouTube Music suelen empezar la canción solos; YouTube normalmente abre los resultados de búsqueda y toca elegir el vídeo. Spotify gratis puede abrir la búsqueda en vez de reproducir.
- Algunos fabricantes (Xiaomi, Huawei, Oppo…) piden además activar "Mostrar ventanas emergentes en segundo plano" en los permisos de la app.
- Esta versión tampoco se ha compilado ni probado en un teléfono real.

## Para que no se corte en segundo plano
- Ajustes del teléfono → Apps → Jarvis → Batería → **Sin restricciones**.
- En Xiaomi, Huawei, Oppo, Samsung: permite "inicio automático" y quita la app de
  las listas de "apps en suspensión / optimización de batería".

## Limitaciones
- El reconocimiento de voz usa el motor de Google del teléfono: necesita internet.
- Escuchar todo el tiempo gasta batería.
- Puede sonar un "pitido" al reiniciar la escucha en algunos teléfonos; la app intenta silenciarlo.
- Esta carpeta no se ha compilado ni probado en un teléfono real todavía.

## Dónde cambiar cosas
- Lógica y diseño: `app/src/main/assets/index.html`
- Logo: `app/src/main/res/drawable/ic_launcher_foreground.xml` (copia en `logo.svg`)
- Nombre de la app: `app/src/main/res/values/strings.xml`
- Burbuja flotante: `app/src/main/java/com/jarvis/assistant/Overlay.kt`
- Abrir apps, canciones y control de música: `app/src/main/java/com/jarvis/assistant/Actions.kt`
- Órdenes de voz (palabras que entiende): función `parseCommand` en `index.html`
- Personalidad: `SYSTEM` en `index.html`
- Servicio de escucha y voz: `app/src/main/java/com/jarvis/assistant/JarvisService.kt`
