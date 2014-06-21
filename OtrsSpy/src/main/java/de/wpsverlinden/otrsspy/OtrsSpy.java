/*
 * OtrsSpy - A simple OTRS queue extractor
 * Copyright (C) 2014 Oliver Verlinden (http://wps-verlinden.de)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package de.wpsverlinden.otrsspy;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

public class OtrsSpy {

    private final String QUEUE_NAME_PATTERN = "<div class=\"OverviewBox ARIARoleMain Small\"><h1>(.+?)</h1>";
    private final String LOGOUT_TOKEN_PATTERN = "Action=Logout;ChallengeToken=(.+?);\"";
    static final Logger logger = Logger.getLogger(OtrsSpy.class);
    private String[] args;
    private String user;
    private String password;
    private String host;
    private String lang;
    private String session;
    private String logoutToken;
    private List<String> queues = new LinkedList<>();

    private static class ExtractException extends Exception {

        public ExtractException(String msg) {
            super(msg);
        }
    }

    public static void main(String[] args) {
        OtrsSpy app = new OtrsSpy(args);
        app.start();
    }

    private OtrsSpy(String[] args) {
        this.args = args;
    }

    private void start() {
        PropertyConfigurator.configure(getClass().getResource("/log4j.properties"));
        try {
            init(args);
            login();
            extractQueues();
            logout();
            printQueues();
        } catch (ExtractException ex) {
            logger.warn(ex.getMessage());
        } catch (MalformedURLException ex) {
            logger.error(ex);
        } catch (IOException ex) {
            logger.error(ex);
        }
    }

    private void init(String[] args) {
        Options options = new Options();
        options.addOption(OptionBuilder.withArgName("host")
                .isRequired()
                .hasArg()
                .withDescription("host running OTRS (e.g. \"http://company.intranet/otrs/\"")
                .create("h"));
        options.addOption(OptionBuilder.withArgName("user")
                .isRequired()
                .hasArg()
                .withDescription("user of any valid oats agent")
                .create("u"));
        options.addOption(OptionBuilder.withArgName("password")
                .isRequired()
                .hasArg()
                .withDescription("password of any valid oats agent")
                .create("p"));
        options.addOption(OptionBuilder.withArgName("language")
                .hasArg()
                .withDescription("language code of the OTRS instance (default: de)")
                .create("l"));
        try {
            CommandLineParser parser = new PosixParser();
            CommandLine cmd = parser.parse(options, args);

            host = cmd.getOptionValue("h");
            user = cmd.getOptionValue("u");
            password = cmd.getOptionValue("p");
            lang = cmd.hasOption("l") ? cmd.getOptionValue("l") : "de";

            if (!host.endsWith("/")) {
                host = host.concat("/");
            }

        } catch (ParseException pe) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("java -jar otrsspy", options, true);
            System.exit(0);
        }


    }

    private void login() throws MalformedURLException, IOException, ExtractException {
        logger.info("Login...");
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

    private String extractSession(HttpURLConnection conn) throws ExtractException {
        String s;
        logger.info("Extracting session token... ");
        String cookie = conn.getHeaderField("Set-Cookie");
        if (cookie == null || cookie.isEmpty()) {
            throw new ExtractException("Could not find session token. Please check user and password.");
        }
        Pattern pattern = Pattern.compile("Session=([a-z0-9]*);");
        Matcher matcher = pattern.matcher(cookie);
        if (matcher.find()) {
            s = matcher.group(1);
            logger.debug("Extract session token " + s);
        } else {
            throw new ExtractException("Could not find session token. Please check user and password.");
        }
        return s;
    }

    private void extractQueues() throws MalformedURLException, IOException {
        logger.info("Extracting queues...");

        String myQueuesLabel = "";
        String lastQueueName = "x";
        int i = 0;
        do {
            String content = probeQueue(i);
            if (logoutToken == null) {
                logger.info("Extracting logout token... ");
                logoutToken = extractLogoutToken(content);
            }


            String name = extractName(content);
            if (name != null) {
                if (myQueuesLabel.isEmpty()) {
                    myQueuesLabel = name;
                } else {
                    queues.add(name);
                    lastQueueName = name;
                }
                logger.debug("Probing queue " + i + " found");
            } else {
                logger.debug("Probing queue " + i + " not found");
            }
            i++;
        } while (!lastQueueName.equals(myQueuesLabel));

        //Remove last element 
        queues.remove(queues.size() - 1);
    }

    private String probeQueue(int i) throws MalformedURLException, IOException {
        URL url = new URL(host + "index.pl?Action=AgentTicketQueue;QueueID=" + i + ";View=");
        logger.debug("Sending probe request " + url.toString());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Cookie", "Session=" + session);
        logger.debug("Receiving logout reply " + conn.getResponseCode());
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
            logger.debug("Extract queue name " + name.substring(separator + 2));
            return name.substring(separator + 2);
        } else {
            return null;
        }
    }

    private String extractLogoutToken(String content) {
        Pattern pattern = Pattern.compile(LOGOUT_TOKEN_PATTERN);
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            logger.debug("Extract logout token " + matcher.group(1));
            return matcher.group(1);
        } else {
            return null;
        }
    }

    private void logout() throws MalformedURLException, IOException {
        URL url = new URL(host + "index.pl?Action=Logout;ChallengeToken=" + logoutToken + ";");
        logger.info("Logout... ");
        logger.debug("Sending logout request " + url.toString());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Cookie", "Session=" + session);
        logger.debug("Receiving logout reply " + conn.getResponseCode());
    }

    private void printQueues() {
        System.out.println("\nSucessfully extracted " + queues.size() + " queues:");
        for (String name : queues) {
            System.out.println(name);
        }
    }
}
