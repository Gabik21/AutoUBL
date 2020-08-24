package uk.co.arcanegames.AutoUBL.tasks;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.logging.Level;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import uk.co.arcanegames.AutoUBL.AutoUBL;

/**
 * This task attempts to update the ban-list from the ban-list server and
 * backs up the ban-list locally.
 *
 * If the ban-list server is not available, it will be reloaded from backup
 *
 * @author XHawk87
 */
public class BanlistUpdater implements Runnable {

    private AutoUBL plugin;
    private BukkitTask autoChecker;

    public BanlistUpdater(AutoUBL plugin) {
        this.plugin = plugin;
    }

    /**
     * Schedule regular updates
     *
     * @param interval How often to update in minutes
     */
    public void schedule(int interval) {
        int ticks = interval * 1200;

        // Stop the current updater from running
        cancel();

        // Schedule the updater to run asynchronously with the new interval
        autoChecker = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this, ticks, ticks);
    }

    /**
     * Schedule an immediate update
     */
    public void download() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this);
    }

    /**
     * Stop the regular updater
     */
    public void cancel() {
        if (autoChecker != null) {
            autoChecker.cancel();
        }
    }

    /**
     * Attempt to download the ban-list from the given stream within the
     * specified time limit
     *
     * @param in The input stream
     * @param bufferSize The size of the data buffer in bytes
     * @param timeout The time limit in server ticks
     * @return The raw data
     * @throws IOException The connection errored or was terminated
     * @throws InterruptedException The time limit was exceeded
     */
    private String downloadBanlist(BufferedReader in, int bufferSize, int timeout) throws IOException, InterruptedException {
        // Set up an interrupt on this thread when the time limit expires
        final Thread iothread = Thread.currentThread();
        BukkitTask timer = new BukkitRunnable() {
            @Override
            public void run() {
                iothread.interrupt();
            }
        }.runTaskLaterAsynchronously(plugin, timeout);

        try {
            char[] buffer = new char[bufferSize];
            int bytesRead;
            StringBuilder sb = new StringBuilder();

            // Loop until interrupted or end of stream
            while ((bytesRead = in.read(buffer)) > 0) {
                // Append what was read to the raw data string
                sb.append(buffer, 0, bytesRead);
            }
            return sb.toString();
        } finally {
            // Whatever happens, make sure the thread doesn't get interrupted!
            timer.cancel();
        }
    }

    @Override
    public void run() {
        // Get the configuration settings
        FileConfiguration config = plugin.getConfig();
        String banlistURL = config.getString("banlist-url");
        int retries = config.getInt("retries", 3);
        int maxBandwidth = config.getInt("max-bandwidth", 64);
        int bufferSize = (maxBandwidth * 1024) / 20;
        int timeout = config.getInt("timeout", 5);

        // Attempt to connect to the banlist server
        URL url;
        String data;
        BufferedReader in;
        try {
            url = new URL(banlistURL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeout * 1000);
            conn.setReadTimeout(timeout * 1000);
            conn.addRequestProperty("Accept-Language", "en-US,en;q=0.8");
            conn.addRequestProperty("User-Agent", "Mozilla");
            conn.addRequestProperty("Referer", "google.com");

            in = new BufferedReader(new InputStreamReader(conn.getInputStream()), bufferSize);

            // While opening connections, the plugin may have been disabled
            if (!plugin.isEnabled()) {
                return;
            }

            // Download banlist
            try {
                data = downloadBanlist(in, bufferSize, timeout * 20);
                plugin.getLogger().info("UBL successfully updated from banlist server");
            } catch (IOException ex) {
                plugin.getLogger().severe("Connection was interrupted while downloading banlist from " + banlistURL);
                data = loadFromBackup();
            } catch (InterruptedException ex) {
                plugin.getLogger().log(Level.SEVERE, "Timed out while waiting for banlist server to send data", ex);
                data = loadFromBackup();
            }

            // Save backup
            saveToBackup(data);
        } catch (MalformedURLException ex) {
            plugin.getLogger().severe("banlist-url in the config.yml is invalid or corrupt. This must be corrected and the config reloaded before the UBL can be updated");
            data = loadFromBackup();
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Banlist server " + banlistURL + " is currently unreachable", ex);
            data = loadFromBackup();
        }

        // Parse banlist data if plugin is still enabled
        if (plugin.isEnabled()) {
            parseData(data);
        }
    }

    private void parseData(final String data) {
        // Schedule to run in the main thread
        new BukkitRunnable() {
            @Override
            public void run() {
                // Split on EOL
                String[] lines = data.split("\\r?\\n");
                if (lines.length < 2) {
                    plugin.getLogger().warning("Banlist is empty!");
                    return;
                }
                plugin.setBanList(lines[0], Arrays.asList(Arrays.copyOfRange(lines, 1, lines.length)));
            }
        }.runTask(plugin);
    }

    /**
     * Load raw ban-list from the backup file, if it exists.
     *
     * If there are any problems, return an empty string
     *
     * @return The raw ban-list, or an empty string
     */
    public String loadFromBackup() {
        File file = new File(plugin.getDataFolder(), "ubl.backup");
        if (!file.exists()) {
            plugin.getLogger().severe("The backup file could not be located. You are running without UBL protection!");
            return "";
        }
        try (BufferedReader in = new BufferedReader(new FileReader(file))) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[8192];

            while (true) {
                int bytesRead = in.read(buffer);
                if (bytesRead == -1) {
                    break;
                }
                sb.append(buffer, 0, bytesRead);
            }

            plugin.getLogger().info("UBL loaded from local backup");
            return sb.toString();
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not load UBL backup. You are running without UBL protection!", ex);
            return "";
        }
    }

    /**
     * Save the raw ban-list data to the backup file
     *
     * This should not be run on the main server thread
     *
     * @param data The raw ban-list data
     */
    public void saveToBackup(String data) {
        File file = new File(plugin.getDataFolder(), "ubl.backup");
        try (BufferedWriter out = new BufferedWriter(new FileWriter(file))) {
            out.write(data);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save UBL backup", ex);
        }
    }
}
