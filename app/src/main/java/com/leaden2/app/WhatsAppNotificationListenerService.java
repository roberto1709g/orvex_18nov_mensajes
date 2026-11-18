package com.leaden2.app;

import android.app.Notification;
import android.app.Person;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.HashMap;
import java.util.Map;

/**
 * Detecta mensajes visibles de WhatsApp y WhatsApp Business.
 * Lee tanto los campos tradicionales como los mensajes MessagingStyle,
 * que algunas versiones de WhatsApp utilizan especialmente en notificaciones agrupadas.
 */
public class WhatsAppNotificationListenerService extends NotificationListenerService {
    private static final String TAG = "ORVEXWhatsAppNotif";
    private static final String PREFS = "leaden2_local_data";
    private static final long DEDUPE_WINDOW_MS = 30_000L;
    private static final String EXTRA_MESSAGES_KEY = "android.messages";
    private final Map<String, Long> processed = new HashMap<>();

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !isAssistantEnabled()) return;

        String pkg = sbn.getPackageName();
        if (!"com.whatsapp".equals(pkg) && !"com.whatsapp.w4b".equals(pkg)) return;

        Notification notification = sbn.getNotification();
        if (notification == null) return;
        Bundle extras = notification.extras;
        if (extras == null) return;

        // Evita resúmenes de grupos y notificaciones que no representan mensajes nuevos.
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;

        MessageDetails messageDetails = extractMessageDetails(extras);
        String sender = clean(messageDetails.sender);
        if (TextUtils.isEmpty(sender)) {
            sender = clean(extras.getCharSequence(Notification.EXTRA_TITLE));
        }
        if (TextUtils.isEmpty(sender) || "WhatsApp".equalsIgnoreCase(sender)) {
            sender = "un contacto";
        }

        String text = clean(messageDetails.text);
        if (TextUtils.isEmpty(text)) {
            text = clean(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        }
        if (TextUtils.isEmpty(text)) {
            text = clean(extras.getCharSequence(Notification.EXTRA_TEXT));
        }
        if (TextUtils.isEmpty(text)) {
            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null && lines.length > 0) {
                text = clean(lines[lines.length - 1]);
            }
        }
        if (TextUtils.isEmpty(text)) {
            Log.d(TAG, "Notificación de WhatsApp recibida sin texto accesible; se omite para evitar un aviso vacío.");
            return;
        }

        // Evita reproducir el mismo evento varias veces cuando WhatsApp actualiza la notificación.
        String key = pkg + "|" + sbn.getId() + "|"
            + (sbn.getTag() == null ? "" : sbn.getTag()) + "|" + sender + "|" + text;
        long now = System.currentTimeMillis();
        synchronized (processed) {
            Long previous = processed.get(key);
            if (previous != null && now - previous < DEDUPE_WINDOW_MS) return;
            processed.put(key, now);
            if (processed.size() > 100) processed.clear();
        }

        Log.i(TAG, "Mensaje WhatsApp detectado; remitente=" + sender
            + ", caracteres=" + text.length());

        Intent intent = new Intent(this, PlaybackService.class);
        intent.setAction(PlaybackService.ACTION_WHATSAPP_ALERT);
        intent.putExtra(PlaybackService.EXTRA_WHATSAPP_SENDER, sender);
        intent.putExtra(PlaybackService.EXTRA_WHATSAPP_TEXT, text);
        try {
            // En Android 8 o superior se solicita el inicio como servicio de primer plano.
            // PlaybackService se promociona inmediatamente al recibir ACTION_WHATSAPP_ALERT.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "No se pudo solicitar el aviso de WhatsApp.", e);
        }
    }

    /**
     * EXTRA_MESSAGES contiene los mensajes individuales de MessagingStyle.
     * Se toma el mensaje más reciente, que puede no estar reflejado en EXTRA_TEXT.
     */
    private MessageDetails extractMessageDetails(Bundle extras) {
        MessageDetails result = new MessageDetails();
        Parcelable[] messages = extras.getParcelableArray(EXTRA_MESSAGES_KEY);
        if (messages == null || messages.length == 0) return result;

        for (int i = messages.length - 1; i >= 0; i--) {
            if (!(messages[i] instanceof Bundle)) continue;
            Bundle message = (Bundle) messages[i];
            String candidateText = clean(message.getCharSequence("text"));
            if (TextUtils.isEmpty(candidateText)) continue;

            result.text = candidateText;
            CharSequence senderName = message.getCharSequence("sender");
            if (!TextUtils.isEmpty(senderName)) {
                result.sender = senderName.toString().trim();
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    Parcelable senderParcelable = message.getParcelable("sender_person");
                    if (senderParcelable instanceof Person) {
                        CharSequence personName = ((Person) senderParcelable).getName();
                        if (!TextUtils.isEmpty(personName)) result.sender = personName.toString().trim();
                    }
                } catch (Exception e) {
                    Log.d(TAG, "No se pudo obtener el remitente individual del mensaje MessagingStyle.");
                }
            }
            return result;
        }
        return result;
    }

    private String clean(CharSequence value) {
        return value == null ? null : value.toString().trim();
    }

    private boolean isAssistantEnabled() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("assistant_enabled", false);
    }

    /** Utilidad para que MainActivity pueda mostrar el estado del permiso. */
    public static boolean isEnabled(android.content.Context context) {
        try {
            String enabled = Settings.Secure.getString(
                context.getContentResolver(),
                "enabled_notification_listeners"
            );
            String component = context.getPackageName() + "/" + WhatsAppNotificationListenerService.class.getName();
            return enabled != null && enabled.toLowerCase().contains(component.toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    private static class MessageDetails {
        String sender;
        String text;
    }
}
