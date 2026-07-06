package org.example;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import com.google.gson.Gson;
import java.security.Security;
import org.apache.http.HttpResponse;

public class Main {

    public static ConcurrentHashMap<String, String> userProfilePics = new ConcurrentHashMap<>();
    public static ConcurrentHashMap<String, String> userSubscriptions = new ConcurrentHashMap<>();
    public static ConcurrentHashMap<String, Long> typingStatus = new ConcurrentHashMap<>();

    private static final String DB_URL = (new java.io.File("/app/data").exists()) 
        ? "jdbc:sqlite:/app/data/chatlounge.db" 
        : "jdbc:sqlite:src/main/resources/chatlounge.db";
    private static PushService pushService;

    public static void main(String[] args) {
        HashMap<String, String> userDatabase = new HashMap<>();
        HashMap<String, Long> userLastSeen = new HashMap<>();
        HashMap<String, String> activeInvites = new HashMap<>();
        HashSet<String> establishedConnections = new HashSet<>();
        HashMap<String, Integer> unreadCounts = new HashMap<>();
        HashMap<String, ArrayList<String>> chatHistories = new HashMap<>();
        HashMap<String, String> userRecoveryIds = new HashMap<>(); // NEW: Recovery ID storage

        Security.addProvider(new BouncyCastleProvider());
        try {
            pushService = new PushService();
            pushService.setSubject("mailto:cellflow24@gmail.com");
            pushService.setPublicKey("BDDhyYsSLzcQFyLfD-r_NUqwFZ9TNxR6woPhXrImD1TGHdEOam7x-yGWPDrsLMPqRh-v-_W7xPXy8PccWuJCnkI");
            pushService.setPrivateKey("a7WkNnBOk0meXEkN-R8doC0rKuk70omQvaEkt-OOiZs");
            System.out.println("✅ Push Service initialized successfully");
        } catch (Exception e) {
            System.err.println("Critical Error starting Push Service: " + e.getMessage());
            e.printStackTrace();
        }

        initializeDatabase(userDatabase, establishedConnections, chatHistories, userRecoveryIds);

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("/public", Location.CLASSPATH);
        });

        app.get("/", ctx -> ctx.redirect("/index.html"));

        app.post("/api/saveSubscription", ctx -> {
            String username = ctx.queryParam("username");
            String subJson = ctx.body();
            if (username != null && !subJson.isEmpty()) {
                String cleanUser = username.trim().toLowerCase();
                userSubscriptions.put(cleanUser, subJson);
                saveSubscriptionToDatabase(cleanUser, subJson);
                ctx.result("SUBSCRIPTION_SAVED");
            } else {
                ctx.status(400).result("FAIL");
            }
        });

        app.get("/api/login", ctx -> {
            String user = ctx.queryParam("username");
            String pass = ctx.queryParam("password");
            if (user != null) user = user.trim().toLowerCase();

            if (userDatabase.containsKey(user) && userDatabase.get(user).equals(pass)) {
                userLastSeen.put(user, System.currentTimeMillis());
                ctx.result("SUCCESS: Access Granted!");
            } else {
                ctx.result("FAIL: Invalid username or password.");
            }
        });

        app.get("/api/register", ctx -> {
            String user = ctx.queryParam("username");
            String pass = ctx.queryParam("password");
            if (user != null) user = user.trim().toLowerCase();

            if (userDatabase.containsKey(user)) {
                ctx.result("FAIL: Username already exists!");
            } else {
                userDatabase.put(user, pass);
                saveUserToDatabase(user, pass);

                // Generate and save unique #ID for new user
                String newId = "#" + java.util.UUID.randomUUID().toString().substring(0, 5).toUpperCase();
                userRecoveryIds.put(user, newId);
                saveRecoveryIdToDatabase(user, newId);

                if (user != null && !user.equals("help")) {
                    establishedConnections.add(user + ":help");
                    establishedConnections.add("help:" + user);
                    saveConnectionToDatabase(user, "help");
                }
                ctx.result("SUCCESS: Account created successfully!");
            }
        });

        app.get("/api/users", ctx -> {
            String viewer = ctx.queryParam("viewer");
            if (viewer != null) viewer = viewer.trim().toLowerCase();

            long currentTime = System.currentTimeMillis();
            StringBuilder responseData = new StringBuilder();

            for (String username : userDatabase.keySet()) {
                if (username.equals(viewer)) {
                    userLastSeen.put(viewer, currentTime);
                }

                long lastSeen = userLastSeen.getOrDefault(username, 0L);
                boolean isOnline = (currentTime - lastSeen) < 7000;

                String unreadKey = viewer + "#" + username;
                int unreads = unreadCounts.getOrDefault(unreadKey, 0);

                String statusFlag = "NONE";
                if (establishedConnections.contains(viewer + ":" + username)) {
                    statusFlag = "CONNECTED";
                } else if (activeInvites.containsKey(username) && activeInvites.get(username).equals(viewer)) {
                    statusFlag = "PENDING";
                }

                String profilePicUrl = "NONE";
                if (statusFlag.equals("CONNECTED") || username.equals(viewer)) {
                    profilePicUrl = userProfilePics.getOrDefault(username, "NONE");
                }

                responseData.append(username).append("###")
                        .append(isOnline ? "true" : "false").append("###")
                        .append(unreads).append("###")
                        .append(statusFlag).append("###")
                        .append(profilePicUrl).append("$$$");
            }

            String finalResult = responseData.toString();
            if (finalResult.endsWith("$$$")) {
                finalResult = finalResult.substring(0, finalResult.length() - 3);
            }
            
            StringBuilder momentsData = new StringBuilder();
            long cutoff = System.currentTimeMillis() - (24 * 60 * 60 * 1000L); 
            try (Connection conn = DriverManager.getConnection(DB_URL)) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM moments WHERE timestamp < ?")) {
                    ps.setLong(1, cutoff);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("SELECT username, targets FROM moments")) {
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        String author = rs.getString("username");
                        String targets = rs.getString("targets");
                        if (author.equals(viewer) || targets.contains(viewer) || targets.equals("all")) {
                            momentsData.append(author).append(",");
                        }
                    }
                }
            } catch (SQLException e) { }
            
            ctx.result(finalResult + "|||" + momentsData.toString());
        });

        // --- NEW SECURITY & SETTINGS ENDPOINTS ---

        app.get("/api/getRecoveryId", ctx -> {
            String user = ctx.queryParam("user");
            if (user != null) {
                ctx.result(userRecoveryIds.getOrDefault(user.trim().toLowerCase(), "NONE"));
            } else {
                ctx.result("NONE");
            }
        });

        app.get("/api/changePassword", ctx -> {
            String user = ctx.queryParam("user");
            String oldP = ctx.queryParam("old");
            String newP = ctx.queryParam("new");
            if (user != null && oldP != null && newP != null) {
                String cleanUser = user.trim().toLowerCase();
                if (oldP.equals(userDatabase.get(cleanUser))) {
                    userDatabase.put(cleanUser, newP);
                    saveUserToDatabase(cleanUser, newP);
                    ctx.result("SUCCESS");
                } else {
                    ctx.result("FAIL: Incorrect current password.");
                }
            } else {
                ctx.result("FAIL");
            }
        });

        app.get("/api/unfriend", ctx -> {
            String user1 = ctx.queryParam("user1");
            String user2 = ctx.queryParam("user2");
            if (user1 != null && user2 != null) {
                String u1 = user1.trim().toLowerCase();
                String u2 = user2.trim().toLowerCase();
                establishedConnections.remove(u1 + ":" + u2);
                establishedConnections.remove(u2 + ":" + u1);
                
                try (Connection conn = DriverManager.getConnection(DB_URL);
                     PreparedStatement ps = conn.prepareStatement("DELETE FROM connections WHERE (user1 = ? AND user2 = ?) OR (user1 = ? AND user2 = ?)")) {
                    ps.setString(1, u1); ps.setString(2, u2);
                    ps.setString(3, u2); ps.setString(4, u1);
                    ps.executeUpdate();
                } catch (SQLException e) {}
                ctx.result("SUCCESS");
            } else {
                ctx.result("FAIL");
            }
        });

        app.get("/api/recoverAccount", ctx -> {
            String user = ctx.queryParam("user");
            String recId = ctx.queryParam("recoveryId");
            if (user != null && recId != null) {
                String cleanUser = user.trim().toLowerCase();
                if (recId.equalsIgnoreCase(userRecoveryIds.get(cleanUser))) {
                    String tempPass = "123456";
                    userDatabase.put(cleanUser, tempPass);
                    saveUserToDatabase(cleanUser, tempPass);
                    
                    // Force inject an automated message from the Help account
                    String helpUser = "help";
                    String roomKey = (helpUser.compareTo(cleanUser) < 0) ? helpUser + "#" + cleanUser : cleanUser + "#" + helpUser;
                    String plainMsg = "🔐 Account recovered. Please go to Settings ⚙️ right now to change your password.";
                    long timestamp = System.currentTimeMillis();
                    String contentPayloadToken = helpUser + ":" + plainMsg + "|~|" + timestamp;

                    chatHistories.putIfAbsent(roomKey, new ArrayList<>());
                    chatHistories.get(roomKey).add(contentPayloadToken);
                    saveMessageToDatabase(roomKey, contentPayloadToken);

                    String unreadTrackingKey = cleanUser + "#" + helpUser;
                    unreadCounts.put(unreadTrackingKey, unreadCounts.getOrDefault(unreadTrackingKey, 0) + 1);
                    
                    ctx.result("SUCCESS: Temporary password is: " + tempPass);
                } else {
                    ctx.result("FAIL: Invalid Username or Recovery ID.");
                }
            } else {
                ctx.result("FAIL");
            }
        });

        // -----------------------------------------

        app.post("/api/uploadProfilePic", ctx -> {
            String user = ctx.queryParam("user");
            String imagePayload = ctx.body();

            if (user == null || imagePayload.isEmpty()) {
                ctx.status(400).result("FAIL: Invalid upload parameters");
                return;
            }

            String cleanUser = user.trim().toLowerCase();
            userProfilePics.put(cleanUser, imagePayload);
            saveProfilePicToDatabase(cleanUser, imagePayload);
            ctx.result("UPLOAD_SUCCESSFUL");
        });

        app.get("/api/sendInvite", ctx -> {
            String fromUser = ctx.queryParam("from");
            String toUser = ctx.queryParam("to");

            if (fromUser == null || toUser == null) {
                ctx.result("FAIL");
                return;
            }

            String cleanFrom = fromUser.trim().toLowerCase();
            String cleanTo = toUser.trim().toLowerCase();

            activeInvites.put(cleanTo, cleanFrom);
            ctx.result("SUCCESS");
        });

        app.post("/api/sendMessage", ctx -> {
            String fromUser = ctx.queryParam("from");
            String toUser = ctx.queryParam("to");
            String messageBody = ctx.body();

            if (fromUser == null || toUser == null || messageBody.isEmpty()) {
                ctx.status(400).result("Invalid Message Parameters");
                return;
            }

            String cleanFrom = fromUser.trim().toLowerCase();
            String cleanTo = toUser.trim().toLowerCase();

            if (!establishedConnections.contains(cleanFrom + ":" + cleanTo)) {
                ctx.status(403).result("Access Blocked: Channel not verified");
                return;
            }

            String roomKey = (cleanFrom.compareTo(cleanTo) < 0) ? cleanFrom + "#" + cleanTo : cleanTo + "#" + cleanFrom;
            
            long timestamp = System.currentTimeMillis();
            String contentPayloadToken = cleanFrom + ":" + messageBody + "|~|" + timestamp;

            chatHistories.putIfAbsent(roomKey, new ArrayList<>());
            chatHistories.get(roomKey).add(contentPayloadToken);
            saveMessageToDatabase(roomKey, contentPayloadToken);

            String unreadTrackingKey = cleanTo + "#" + cleanFrom;
            unreadCounts.put(unreadTrackingKey, unreadCounts.getOrDefault(unreadTrackingKey, 0) + 1);

            String recipientSubJson = userSubscriptions.get(cleanTo);
            if (recipientSubJson != null && pushService != null) {
                final String subJsonFinal = recipientSubJson;
                final String fromFinal = cleanFrom;
                final String toFinal = cleanTo;
                final String msgFinal = messageBody;

                new Thread(() -> {
                    try {
                        Subscription sub = new Gson().fromJson(subJsonFinal, Subscription.class);

                        String snippet = msgFinal.startsWith("IMG_ATTACHMENT_DATA:") ? "🖼️ Sent an image" : msgFinal;
                        if (snippet.startsWith("E2E::")) snippet = "🔐 Encrypted Message";
                        if (snippet.length() > 60) snippet = snippet.substring(0, 60) + "...";

                        String payload = String.format(
                            "{\"title\":\"💬 %s\", \"body\":\"%s\", \"url\":\"/\"}",
                            capitalizeFirstLetter(fromFinal),
                            snippet.replace("\"", "'")
                        );

                        Notification notification = new Notification(
                            sub.endpoint,
                            sub.keys.p256dh,
                            sub.keys.auth,
                            payload.getBytes("UTF-8"),
                            86400  // TTL = 24 hours
                        );
                        HttpResponse response = pushService.send(notification);
                        int statusCode = response.getStatusLine().getStatusCode();

                        if (statusCode == 410 || statusCode == 404) {
                            userSubscriptions.remove(toFinal);
                            deleteSubscriptionFromDatabase(toFinal);
                            System.out.println("Cleaned expired subscription for: " + toFinal);
                        }

                    } catch (Exception e) {
                        System.err.println("Push thread error for " + toFinal + ": " + e.getMessage());
                    }
                }).start();
            }

            ctx.result("MESSAGE_DISPATCHED_SUCCESSFULLY");
        });
        
        app.get("/api/checkInvites", ctx -> {
            String currentUserName = ctx.queryParam("user");
            if (currentUserName == null) {
                ctx.result("NONE");
                return;
            }
            String cleanUser = currentUserName.trim().toLowerCase();
            if (activeInvites.containsKey(cleanUser)) {
                ctx.result("PENDING:" + activeInvites.get(cleanUser));
            } else {
                ctx.result("NONE");
            }
        });

        app.get("/api/respondInvite", ctx -> {
            String currentUserName = ctx.queryParam("user");
            String action = ctx.queryParam("action");
            if (currentUserName == null || action == null) {
                ctx.result("FAIL");
                return;
            }

            String cleanUser = currentUserName.trim().toLowerCase();
            String sender = activeInvites.remove(cleanUser);

            if (action.equalsIgnoreCase("accept") && sender != null) {
                establishedConnections.add(cleanUser + ":" + sender);
                establishedConnections.add(sender + ":" + cleanUser);
                saveConnectionToDatabase(cleanUser, sender);
            }
            ctx.result("SUCCESS");
        });

        app.get("/api/checkConnection", ctx -> {
            String user1 = ctx.queryParam("user1");
            String user2 = ctx.queryParam("user2");

            if (user1 == null || user2 == null) {
                ctx.result("FALSE");
                return;
            }

            String cleanUser1 = user1.trim().toLowerCase();
            String cleanUser2 = user2.trim().toLowerCase();

            if (establishedConnections.contains(cleanUser1 + ":" + cleanUser2)) {
                ctx.result("TRUE");
            } else {
                ctx.result("FALSE");
            }
        });

        app.get("/api/setTyping", ctx -> {
            String from = ctx.queryParam("from");
            String to = ctx.queryParam("to");
            String typingStr = ctx.queryParam("typing");

            if (from != null && to != null) {
                String cleanFrom = from.trim().toLowerCase();
                String cleanTo = to.trim().toLowerCase();
                if ("true".equals(typingStr)) {
                    typingStatus.put(cleanFrom + "#" + cleanTo, System.currentTimeMillis());
                } else {
                    typingStatus.remove(cleanFrom + "#" + cleanTo);
                }
                ctx.result("OK");
            } else {
                ctx.status(400).result("FAIL");
            }
        });

        app.get("/api/getMessages", ctx -> {
            String from = ctx.queryParam("from").trim().toLowerCase();
            String to = ctx.queryParam("to").trim().toLowerCase();

            String roomKey = (from.compareTo(to) < 0) ? from + "#" + to : to + "#" + from;
            unreadCounts.put(from + "#" + to, 0); 

            ArrayList<String> messages = chatHistories.getOrDefault(roomKey, new ArrayList<>());

            long lastTypingTime = typingStatus.getOrDefault(to + "#" + from, 0L);
            boolean isTyping = (System.currentTimeMillis() - lastTypingTime) < 3000; 

            int unreadByTo = unreadCounts.getOrDefault(to + "#" + from, 0);

            ArrayList<String> outputData = new ArrayList<>(messages);
            outputData.add("META::" + isTyping + "::" + unreadByTo);

            ctx.result(String.join("\n", outputData));
        });

        app.get("/api/deleteUser", ctx -> {
            String admin = ctx.queryParam("admin");
            String target = ctx.queryParam("target");

            if (admin == null || target == null || !admin.trim().toLowerCase().equals("help")) {
                ctx.status(403).result("UNAUTHORIZED");
                return;
            }

            String cleanTarget = target.trim().toLowerCase();
            if (cleanTarget.equals("help")) {
                ctx.status(403).result("CANNOT_DELETE_ADMIN");
                return;
            }

            try (Connection conn = DriverManager.getConnection(DB_URL)) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM users WHERE username = ?")) { ps.setString(1, cleanTarget); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM profile_pics WHERE username = ?")) { ps.setString(1, cleanTarget); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM subscriptions WHERE username = ?")) { ps.setString(1, cleanTarget); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM connections WHERE user1 = ? OR user2 = ?")) { ps.setString(1, cleanTarget); ps.setString(2, cleanTarget); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM moments WHERE username = ?")) { ps.setString(1, cleanTarget); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM user_recovery WHERE username = ?")) { ps.setString(1, cleanTarget); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM messages WHERE room_key LIKE ? OR room_key LIKE ?")) { 
                    ps.setString(1, cleanTarget + "#%"); 
                    ps.setString(2, "%#" + cleanTarget); 
                    ps.executeUpdate(); 
                }
            } catch (SQLException e) { System.err.println("DB Delete Error: " + e.getMessage()); }

            userDatabase.remove(cleanTarget);
            userLastSeen.remove(cleanTarget);
            activeInvites.remove(cleanTarget);
            userProfilePics.remove(cleanTarget);
            userSubscriptions.remove(cleanTarget);
            userRecoveryIds.remove(cleanTarget);
            
            establishedConnections.removeIf(c -> c.startsWith(cleanTarget + ":") || c.endsWith(":" + cleanTarget));
            chatHistories.keySet().removeIf(key -> key.startsWith(cleanTarget + "#") || key.endsWith("#" + cleanTarget));
            unreadCounts.keySet().removeIf(key -> key.startsWith(cleanTarget + "#") || key.endsWith("#" + cleanTarget));
            typingStatus.keySet().removeIf(key -> key.startsWith(cleanTarget + "#") || key.endsWith("#" + cleanTarget));

            ctx.result("DELETED");
        });

        app.post("/api/uploadMoment", ctx -> {
            String from = ctx.queryParam("from");
            String targets = ctx.queryParam("targets");
            String payload = ctx.body();
            long timestamp = System.currentTimeMillis();

            if (from != null && targets != null && !payload.isEmpty()) {
                try (Connection conn = DriverManager.getConnection(DB_URL);
                     PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO moments VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, from.trim().toLowerCase());
                    ps.setString(2, targets.trim().toLowerCase());
                    ps.setString(3, payload);
                    ps.setLong(4, timestamp);
                    ps.executeUpdate();
                    ctx.result("SUCCESS");
                } catch (SQLException e) { ctx.status(500).result("FAIL"); }
            } else {
                ctx.status(400).result("FAIL");
            }
        });

        app.get("/api/viewMoment", ctx -> {
            String author = ctx.queryParam("author");
            if (author == null) return;
            try (Connection conn = DriverManager.getConnection(DB_URL);
                 PreparedStatement ps = conn.prepareStatement("SELECT payload FROM moments WHERE username = ?")) {
                ps.setString(1, author.trim().toLowerCase());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    ctx.result(rs.getString("payload"));
                    return;
                }
            } catch (SQLException e) { e.printStackTrace(); }
            ctx.result("NONE");
        });
        
        String port = System.getenv("PORT");
        if (port != null) {
            app.start(Integer.parseInt(port));
        } else {
            app.start(7070);
        }
    }

    private static void initializeDatabase(HashMap<String, String> userDatabase, HashSet<String> establishedConnections, HashMap<String, ArrayList<String>> chatHistories, HashMap<String, String> userRecoveryIds) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            
            stmt.execute("CREATE TABLE IF NOT EXISTS users (username TEXT PRIMARY KEY, password TEXT);");
            stmt.execute("CREATE TABLE IF NOT EXISTS connections (user1 TEXT, user2 TEXT, PRIMARY KEY (user1, user2));");
            stmt.execute("CREATE TABLE IF NOT EXISTS messages (id INTEGER PRIMARY KEY AUTOINCREMENT, room_key TEXT, token TEXT);");
            stmt.execute("CREATE TABLE IF NOT EXISTS profile_pics (username TEXT PRIMARY KEY, payload TEXT);");
            stmt.execute("CREATE TABLE IF NOT EXISTS subscriptions (username TEXT PRIMARY KEY, sub_json TEXT);");
            stmt.execute("CREATE TABLE IF NOT EXISTS moments (username TEXT PRIMARY KEY, targets TEXT, payload TEXT, timestamp INTEGER);");
            stmt.execute("CREATE TABLE IF NOT EXISTS user_recovery (username TEXT PRIMARY KEY, recovery_id TEXT);"); // NEW DB Table

            stmt.execute("INSERT OR IGNORE INTO users (username, password) VALUES ('help', '1234');");

            ResultSet rsUsers = stmt.executeQuery("SELECT username, password FROM users;");
            while (rsUsers.next()) userDatabase.put(rsUsers.getString("username"), rsUsers.getString("password"));

            ResultSet rsConn = stmt.executeQuery("SELECT user1, user2 FROM connections;");
            while (rsConn.next()) {
                establishedConnections.add(rsConn.getString("user1") + ":" + rsConn.getString("user2"));
                establishedConnections.add(rsConn.getString("user2") + ":" + rsConn.getString("user1"));
            }

            ResultSet rsRec = stmt.executeQuery("SELECT username, recovery_id FROM user_recovery;");
            while (rsRec.next()) userRecoveryIds.put(rsRec.getString("username"), rsRec.getString("recovery_id"));

            // Retroactively generate missing #IDs for older users
            for (String u : userDatabase.keySet()) {
                if (!userRecoveryIds.containsKey(u)) {
                    String newId = "#" + java.util.UUID.randomUUID().toString().substring(0, 5).toUpperCase();
                    userRecoveryIds.put(u, newId);
                    try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO user_recovery VALUES (?, ?)")) {
                        ps.setString(1, u); ps.setString(2, newId); ps.executeUpdate();
                    }
                }
            }

            // --- THE NEW 30-DAY AUTO-CLEANUP SWEEP ---
            ResultSet rsMsg = stmt.executeQuery("SELECT id, room_key, token FROM messages ORDER BY id ASC;");
            long thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000L);
            ArrayList<Integer> idsToDelete = new ArrayList<>();

            while (rsMsg.next()) {
                int msgId = rsMsg.getInt("id");
                String roomKey = rsMsg.getString("room_key");
                String token = rsMsg.getString("token");

                long ts = System.currentTimeMillis(); 
                try {
                    int idx = token.lastIndexOf("|~|");
                    if (idx != -1) {
                        ts = Long.parseLong(token.substring(idx + 3));
                    }
                } catch (Exception e) {}

                if (ts < thirtyDaysAgo) {
                    idsToDelete.add(msgId); // Mark for permanent deletion
                } else {
                    chatHistories.putIfAbsent(roomKey, new ArrayList<>());
                    chatHistories.get(roomKey).add(token); // Load into active memory
                }
            }

            // Execute the bulk database wipe
            if (!idsToDelete.isEmpty()) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM messages WHERE id = ?")) {
                    for (int id : idsToDelete) {
                        ps.setInt(1, id);
                        ps.executeUpdate();
                    }
                }
                System.out.println("🧹 Cleaned up " + idsToDelete.size() + " messages older than 30 days.");
            }

            ResultSet rsPics = stmt.executeQuery("SELECT username, payload FROM profile_pics;");
            while (rsPics.next()) userProfilePics.put(rsPics.getString("username"), rsPics.getString("payload"));

            ResultSet rsSub = stmt.executeQuery("SELECT username, sub_json FROM subscriptions;");
            while (rsSub.next()) userSubscriptions.put(rsSub.getString("username"), rsSub.getString("sub_json"));

        } catch (SQLException e) { System.err.println(e.getMessage()); }
    }

    private static void saveUserToDatabase(String u, String p) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO users VALUES (?, ?)")) {
            ps.setString(1, u); ps.setString(2, p); ps.executeUpdate();
        } catch (SQLException e) { System.err.println(e.getMessage()); }
    }

    private static void saveRecoveryIdToDatabase(String u, String id) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO user_recovery VALUES (?, ?)")) {
            ps.setString(1, u); ps.setString(2, id); ps.executeUpdate();
        } catch (SQLException e) { System.err.println(e.getMessage()); }
    }

    private static void saveConnectionToDatabase(String u1, String u2) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO connections VALUES (?, ?)")) {
            ps.setString(1, u1); ps.setString(2, u2); ps.executeUpdate();
        } catch (SQLException e) { System.err.println(e.getMessage()); }
    }

    private static void saveMessageToDatabase(String r, String t) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("INSERT INTO messages (room_key, token) VALUES (?, ?)")) {
            ps.setString(1, r); ps.setString(2, t); ps.executeUpdate();
        } catch (SQLException e) { System.err.println(e.getMessage()); }
    }

    private static void saveProfilePicToDatabase(String u, String p) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO profile_pics VALUES (?, ?)")) {
            ps.setString(1, u); ps.setString(2, p); ps.executeUpdate();
        } catch (SQLException e) { System.err.println(e.getMessage()); }
    }

    private static void saveSubscriptionToDatabase(String u, String s) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO subscriptions VALUES (?, ?)")) {
            ps.setString(1, u); ps.setString(2, s); ps.executeUpdate();
        } catch (SQLException e) { System.err.println(e.getMessage()); }
    }

    private static void deleteSubscriptionFromDatabase(String u) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("DELETE FROM subscriptions WHERE username = ?")) {
            ps.setString(1, u);
            ps.executeUpdate();
        } catch (SQLException e) { System.err.println(e.getMessage()); }
    }

    private static String capitalizeFirstLetter(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}
