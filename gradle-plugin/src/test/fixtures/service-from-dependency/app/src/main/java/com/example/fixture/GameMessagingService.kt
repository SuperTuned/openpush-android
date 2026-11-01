package com.example.fixture

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class GameMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        GameInbox.store(message.data)
    }
}
