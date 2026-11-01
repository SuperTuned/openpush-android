package com.example.fixture;

import android.util.Log;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class GameMessagingService extends FirebaseMessagingService {
    @Override
    public void onMessageReceived(RemoteMessage message) {
        Log.i("Game", "push " + message.getData());
    }

    @Override
    public void onNewToken(String token) {
        Log.i("Game", "token " + token);
    }
}
