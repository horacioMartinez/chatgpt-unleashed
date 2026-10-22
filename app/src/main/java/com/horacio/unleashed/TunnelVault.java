package com.horacio.unleashed;

import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Runtime credential stays encrypted in app-private storage; never returned by phone tools. */
public final class TunnelVault {
  private static final String ALIAS = "unleashed-tunnel-key";
  private final android.content.SharedPreferences prefs;

  public TunnelVault(Context context) {
    prefs = context.getSharedPreferences("chatgpt-tunnel", Context.MODE_PRIVATE);
  }

  private javax.crypto.SecretKey encryptionKey() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (store.containsAlias(ALIAS)) return (javax.crypto.SecretKey) store.getKey(ALIAS, null);
    KeyGenerator generator =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    generator.init(
        new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
    return generator.generateKey();
  }

  public String tunnel() {
    return prefs.getString("id", "");
  }

  public boolean configured() {
    return !tunnel().isEmpty() && prefs.contains("ciphertext");
  }

  public void save(String id, String newKey) throws Exception {
    if (!id.matches("tunnel_[a-f0-9]{32}"))
      throw new IllegalArgumentException("Enter the tunnel ID from OpenAI Platform.");
    if (newKey.isEmpty()) {
      if (!configured() || !id.equals(tunnel()))
        throw new IllegalArgumentException("Enter a runtime API key for this tunnel.");
      return;
    }
    if (!newKey.startsWith("sk-")
        || newKey.length() < 20
        || newKey.length() > 4096
        || newKey.contains("\n")
        || newKey.contains("\r"))
      throw new IllegalArgumentException("Enter a valid runtime API key.");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, encryptionKey());
    byte[] encrypted = cipher.doFinal(newKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    if (!prefs
        .edit()
        .putString("id", id)
        .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
        .putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        .commit()) throw new java.io.IOException("Could not save tunnel credentials");
  }

  public String key() throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        encryptionKey(),
        new GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)));
    return new String(
        cipher.doFinal(Base64.decode(prefs.getString("ciphertext", ""), Base64.NO_WRAP)),
        java.nio.charset.StandardCharsets.UTF_8);
  }

  public void clear() throws Exception {
    prefs.edit().clear().commit();
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
  }
}
