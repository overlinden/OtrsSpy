package de.wpsverlinden.ortsspy;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OtrsSpy {

    private final String QUEUE_NAME_PATTERN = "<div class=\"OverviewBox ARIARoleMain Small\"><h1>(.+?)</h1>";
    private final String LOGOUT_TOKEN_PATTERN = "Action=Logout;ChallengeToken=(.+?);\"";
    private String[] args;
    private String user;
    private String password;
    private String host;
    private String lang = "de";
    private String session;
    private String logoutToken;
    private List<String> queues = new LinkedList<>();

    public static void main(String[] args) {
        OtrsSpy app = new OtrsSpy(args);
        app.start();
    }

    private OtrsSpy(String[] args) {
        this.args = args;
    }

    private void start() {
        System.out.println("OtrsSpy 1.0 - written by Oliver Verlinden (http://wps-verlinden.de)");
        try {
            init(args);
            login();
            extractQueues();
            logout();
            printQueues();
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    private void init(String[] args) throws Exception {
        if (args.length == 0) {
            printHelp();
            System.exit(0);
        }

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-u")) {
                user = args[++i];
            }
            if (args[i].equals("-p")) {
                password = args[++i];
            }
            if (args[i].equals("-h")) {
                host = args[++i];
            }
            if (args[i].equals("-l")) {
                lang = args[++i];
            }
        }
        if (user == null || user.isEmpty()) {
            throw new Exception("No user given");
        }
        if (password == null || password.isEmpty()) {
            throw new Exception("No password given");
        }
        if (host == null || host.isEmpty()) {
            throw new Exception("No host given");
        }
        if (!host.endsWith("/")) {
            host = host.concat("/");
        }
    }

    private void login() throws Exception {
        System.out.println("Requesting login...");
        HttpURLConnection conn = (HttpURLConnection) (new URL(host + "index.pl")).openConnection();
        conn.setRequestMethod("POST");
        conn.setInstanceFollowRedirects(false);
        conn.setDoOutput(true);
        try (DataOutputStream wr = new DataOutputStream(conn.getOutputStream())) {
            wr.writeBytes("Action=Login&RequestedURL=&Lang=" + lang + "&TimeOffset=-120&User=" + user + "&Password=" + password);
            wr.flush();
        }
        session = extractSession(conn);
    }

    private String extractSession(HttpURLConnection conn) throws Exception {
        String s;
        System.out.print("Extracting session token... ");
        String cookie = conn.getHeaderField("Set-Cookie");
        if (cookie == null || cookie.isEmpty()) {
            System.out.println("failed");
            throw new Exception("Could not find session token. Please check user and password.");
        }
        Pattern pattern = Pattern.compile("Session=([a-z0-9]*);");
        Matcher matcher = pattern.matcher(cookie);
        if (matcher.find()) {
            s = matcher.group(1);
            System.out.println(s);
        } else {
            System.out.println("failed");
            throw new Exception("Could not find session token. Please check user and password.");
        }
        return s;
    }

    private void extractQueues() throws Exception {
        System.out.println("Extracting queues...");

        String myQueuesLabel = "";
        String lastQueueName = "x";
        int i = 0;
        do {
            String content = probeQueue(i);
            if (logoutToken == null) {
                System.out.print("Extracting logout token... ");
                logoutToken = extractLogoutToken(content);
                System.out.println(logoutToken != null ? logoutToken : "failed");
            }
            System.out.print("Probing queue " + i + "... ");

            String name = extractName(content);
            if (name != null) {
                if (myQueuesLabel.isEmpty()) {
                    myQueuesLabel = name;
                } else {
                    queues.add(name);
                    lastQueueName = name;
                }
                System.out.println("found");
            } else {
                System.out.println("not found");
            }
            i++;
        } while (!lastQueueName.equals(myQueuesLabel));

        //Remove last element 
        queues.remove(queues.size() - 1);
    }

    private String probeQueue(int i) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) (new URL(host + "index.pl?Action=AgentTicketQueue;QueueID=" + i + ";View=")).openConnection();
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Cookie", "Session=" + session);

        return convertToString(conn.getInputStream());
    }

    private String convertToString(InputStream inputStream) throws IOException {
        BufferedReader rd = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder sb = new StringBuilder();
        String line;

        while ((line = rd.readLine()) != null) {
            sb.append(line.trim());
        }
        return sb.toString();
    }

    private String extractName(String content) {
        Pattern pattern = Pattern.compile(QUEUE_NAME_PATTERN);
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            String name = matcher.group(1);
            int separator = name.indexOf(":");
            return name.substring(separator + 2);
        } else {
            return null;
        }
    }

    private String extractLogoutToken(String content) {
        Pattern pattern = Pattern.compile(LOGOUT_TOKEN_PATTERN);
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        } else {
            return null;
        }
    }

    private void logout() throws Exception {
        System.out.print("Requesting logout... ");
        HttpURLConnection conn = (HttpURLConnection) (new URL(host + "index.pl?Action=Logout;ChallengeToken=" + logoutToken + ";")).openConnection();
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Cookie", "Session=" + session);
        System.out.println(conn.getResponseCode() == 200 ? "success" : "failed");
    }

    private void printHelp() {
        System.out.println("Usage: java -jar otrsspy -h <host> -u <user> -p <password> [-l <lang>]");
        System.out.println("-h <host>              host running OTRS (e.g. \"http://company.intranet/otrs/\"");
        System.out.println("-u <user>              user of any valid oats agent");
        System.out.println("-p <password>          password of any valid oats agent");
        System.out.println("-l <lang>              language code of the OTRS instance (default: de)");
    }

    private void printQueues() {
        System.out.println("\nSucessfully extracted " + queues.size() + " queues:");
        for (String name : queues) {
            System.out.println(name);
        }
    }
}
