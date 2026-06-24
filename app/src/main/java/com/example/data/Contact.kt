package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val phoneNumber: String,
    val imagePath: String?, // Internal file path to contact photo
    val voiceTagPath: String? // Internal file path to recorded voice tag (PCM or WAV)
)
