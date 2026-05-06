package Sdf1_login;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class EmailManager {

    private final ConfigManager config;

    public EmailManager(ConfigManager config) {
        this.config = config;
    }

    public void sendTempPassword(String toEmail,
                                 String playerName,
                                 String tempPassword) {
        String subject = "[Sdf1_login] 密码重置 - "
                + playerName;
        String body = "玩家: " + playerName
                + "\r\n临时密码: " + tempPassword
                + "\r\n有效期: 5分钟"
                + "\r\n使用后自动失效"
                + "\r\n请尽快登录并修改密码";
        sendEmail(toEmail, subject, body);
    }

    public void sendVerifyCode(String toEmail,
                               String playerName,
                               String code) {
        String subject = "[Sdf1_login] 验证码 - "
                + playerName;
        String body = "玩家: " + playerName
                + "\r\n验证码: " + code
                + "\r\n有效期: 5分钟";
        sendEmail(toEmail, subject, body);
    }

    private void sendEmail(String toEmail,
                           String subject, String body) {
        new Thread(() -> {
            try {
                String host = config.getSmtp("smtp地址");
                int port = 465;
                try {
                    port = Integer.parseInt(
                            config.getSmtp("smtp端口"));
                } catch (Exception ignored) {}
                String from = config.getSmtp("发件邮箱");
                String pass = config.getSmtp("授权码");

                if (host.isEmpty() || from.isEmpty()
                        || pass.isEmpty()) return;
                if (toEmail == null || toEmail.isEmpty())
                    return;

                SSLSocketFactory factory =
                        (SSLSocketFactory) SSLSocketFactory
                                .getDefault();
                SSLSocket socket = (SSLSocket) factory
                        .createSocket(host, port);

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                socket.getInputStream(),
                                StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(
                                socket.getOutputStream(),
                                StandardCharsets.UTF_8));

                readResponse(reader);
                sendCmd(writer, "EHLO localhost");
                readResponse(reader);
                sendCmd(writer, "AUTH LOGIN");
                readResponse(reader);
                sendCmd(writer, Base64.getEncoder()
                        .encodeToString(from.getBytes(
                                StandardCharsets.UTF_8)));
                readResponse(reader);
                sendCmd(writer, Base64.getEncoder()
                        .encodeToString(pass.getBytes(
                                StandardCharsets.UTF_8)));
                readResponse(reader);
                sendCmd(writer,
                        "MAIL FROM:<" + from + ">");
                readResponse(reader);
                sendCmd(writer,
                        "RCPT TO:<" + toEmail + ">");
                readResponse(reader);
                sendCmd(writer, "DATA");
                readResponse(reader);

                String encodedSubject = Base64.getEncoder()
                        .encodeToString(subject.getBytes(
                                StandardCharsets.UTF_8));

                sendCmd(writer, "From: " + from);
                sendCmd(writer, "To: " + toEmail);
                sendCmd(writer, "Subject: =?UTF-8?B?"
                        + encodedSubject + "?=");
                sendCmd(writer, "MIME-Version: 1.0");
                sendCmd(writer,
                        "Content-Type: text/plain;"
                                + " charset=UTF-8");
                sendCmd(writer, "");
                sendCmd(writer, body);
                sendCmd(writer, ".");
                readResponse(reader);
                sendCmd(writer, "QUIT");
                readResponse(reader);

                socket.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void sendCmd(BufferedWriter w, String cmd)
            throws IOException {
        w.write(cmd + "\r\n");
        w.flush();
    }

    private String readResponse(BufferedReader r)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line).append("\n");
            if (line.length() < 4) break;
            if (line.charAt(3) == ' ') break;
        }
        return sb.toString();
    }
}
