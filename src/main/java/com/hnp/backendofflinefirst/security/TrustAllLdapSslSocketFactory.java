package com.hnp.backendofflinefirst.security;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * JNDI LDAP {@code java.naming.ldap.factory.socket} for {@code ldaps://} when the AD
 * certificate is self-signed. Use only on trusted internal networks.
 */
public final class TrustAllLdapSslSocketFactory extends SSLSocketFactory {

    private static final SSLSocketFactory DELEGATE = createDelegate();

    private static SSLSocketFactory createDelegate() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new SecureRandom());
            return context.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create trust-all LDAP SSL socket factory", e);
        }
    }

    public static SSLSocketFactory getDefault() {
        return DELEGATE;
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return DELEGATE.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return DELEGATE.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return DELEGATE.createSocket(s, host, port, autoClose);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return DELEGATE.createSocket(host, port);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return DELEGATE.createSocket(host, port, localHost, localPort);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return DELEGATE.createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        return DELEGATE.createSocket(address, port, localAddress, localPort);
    }
}
