# Android Companion App

App Android para Proyecto Twister.

Responsabilidades:

- Conectarse por BLE al ESP32-C6.
- Escuchar notificaciones seleccionadas (llamadas, WhatsApp, Telegram).
- Traducir eventos de navegacion a mensajes TURN.
- Mantener servicio foreground durante conduccion.

## Estado

- Servicio foreground funcional con heartbeat periodico.
- NotificationListener filtrando llamadas, WhatsApp y Telegram.
- Codificador TLV v1 para TURN/NOTIF/HEARTBEAT.
- Envio a BLE actualmente en modo placeholder (log de frame hex).

## Build en Windows 11

1. Abrir `android-app` con Android Studio o VS Code + Gradle/JDK 17.
2. Sincronizar Gradle.
3. Compilar `app` en debug.
4. En el telefono, otorgar permisos Bluetooth/notificaciones y habilitar Notification Listener.
