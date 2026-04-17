package com.stealthagent.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.sqlcipher.database.SupportFactory
import java.security.SecureRandom
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DiModule {

    @Provides
    @Singleton
    @AgentPrefs
    fun providePrefs(@ApplicationContext context: Context): SharedPreferences {
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            "agent_prefs", masterKey, context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context, @AgentPrefs prefs: SharedPreferences): AgentDatabase {
        val key = "db_passphrase"
        var passphrase = prefs.getString(key, null)
        if (passphrase == null) {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            passphrase = bytes.joinToString("") { "%02x".format(it) }
            prefs.edit().putString(key, passphrase).apply()
        }
        val factory = SupportFactory(passphrase.toByteArray())
        return Room.databaseBuilder(context, AgentDatabase::class.java, "agent.db")
            .openHelperFactory(factory)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideDao(db: AgentDatabase): AgentDao = db.agentDao()
}
