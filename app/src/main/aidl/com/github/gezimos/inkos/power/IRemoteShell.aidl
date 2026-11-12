package com.github.gezimos.inkos.power;

/**
 * Runs one shell command inside a Shizuku UserService (uid 2000).
 *
 * The reply is "<exitCode>\n<stdout+stderr>"; the exit code is the first line so
 * the caller never has to guess whether output means success.
 */
interface IRemoteShell {
    String run(String command);
    int getUid();
}
