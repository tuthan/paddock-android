# sshlib resolves ciphers, MACs and key exchanges by class name (Class.forName in BlockCipherFactory and kin),
# so R8 cannot see them as reachable. Everything else in the library is shrunk normally.
-keep class com.trilead.ssh2.crypto.cipher.** { *; }
-keep class com.trilead.ssh2.crypto.digest.** { *; }
-keep class com.trilead.ssh2.crypto.dh.** { *; }
-keep class com.trilead.ssh2.signature.** { *; }

# Deliberately absent (docs/ssh-library-decision.md): sshlib's ML-KEM implementation, and annotation-only
# classes that tink and the test libraries reference.
-dontwarn asia.hombre.kyber.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
