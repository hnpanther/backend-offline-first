Test fixtures for the Active Directory TLS trust (`LdapConnectionShapeTest`).

Two self-signed server certificates, `dc1.site.test` and `dc2.site.test`, each in its own
PKCS12 with its private key, and a truststore that holds only dc1's certificate. All passwords
are `changeit`. Generated once with keytool, valid for a hundred years; nothing here is a secret
and nothing here is used outside the test suite.

```
keytool -genkeypair -alias dc1 -keyalg RSA -keysize 2048 -dname "CN=dc1.site.test" \
  -ext "SAN=dns:dc1.site.test" -validity 36500 -keystore dc1-key.p12 -storetype PKCS12 \
  -storepass changeit -keypass changeit
keytool -genkeypair -alias dc2 ... -keystore dc2-key.p12 ...
keytool -exportcert -alias dc1 -keystore dc1-key.p12 -storepass changeit -rfc -file dc1.cer
keytool -importcert -noprompt -alias dc1 -file dc1.cer -keystore truststore-dc1.p12 \
  -storetype PKCS12 -storepass changeit
```
