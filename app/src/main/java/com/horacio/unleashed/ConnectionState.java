package com.horacio.unleashed;

/** Display states distinguish a live backend from account access and startup intent. */
final class ConnectionState {
  enum Backend {
    SETUP_REQUIRED,
    PAUSED,
    WAITING,
    RUNNING
  }

  enum ChatGPT {
    NOT_LINKED,
    PAUSED,
    CONNECTING,
    CONNECTED,
    NEEDS_ATTENTION
  }

  static Backend backend(boolean permission, boolean allowed, boolean connected) {
    if (!permission) return Backend.SETUP_REQUIRED;
    if (!allowed) return Backend.PAUSED;
    return connected ? Backend.RUNNING : Backend.WAITING;
  }

  static ChatGPT chatGPT(
      boolean configured, boolean enabled, boolean active, boolean online, boolean rejected) {
    if (!configured) return ChatGPT.NOT_LINKED;
    if (!enabled) return ChatGPT.PAUSED;
    if (rejected) return ChatGPT.NEEDS_ATTENTION;
    return active && online ? ChatGPT.CONNECTED : ChatGPT.CONNECTING;
  }

  static int completedSetupSteps(Backend backend, ChatGPT chatGPT) {
    return (backend == Backend.RUNNING ? 1 : 0) + (chatGPT == ChatGPT.CONNECTED ? 1 : 0);
  }
}
