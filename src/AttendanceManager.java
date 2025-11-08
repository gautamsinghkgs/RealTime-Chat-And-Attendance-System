import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * AttendanceManager handles attendance file writes and session headers.
 * Simple and thread-safe using synchronized methods.
 */
public class AttendanceManager {
    private final File file;
    private final SimpleDateFormat sessionSdf = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
    private final SimpleDateFormat entrySdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public AttendanceManager(String filename) {
        file = new File(filename);
    }

    /**
     * Adds a new session header (called when server starts).
     */
    public synchronized void startSession() {
        try (FileWriter fw = new FileWriter(file, true);
             BufferedWriter bw = new BufferedWriter(fw)) {
            String header = "\n===== Attendance Session Started at " + sessionSdf.format(new Date()) + " =====\n";
            bw.write(header);
            bw.flush();
        } catch (IOException e) {
            System.err.println("AttendanceManager: could not write session header: " + e.getMessage());
        }
    }

    /**
     * Logs attendance of a student (name and UID).
     */
    public synchronized void logAttendance(String name, String uid) {
        try (FileWriter fw = new FileWriter(file, true);
             BufferedWriter bw = new BufferedWriter(fw)) {
            String entry = name + " (" + uid + ") - Present [" + entrySdf.format(new Date()) + "]\n";
            bw.write(entry);
            bw.flush();
        } catch (IOException e) {
            System.err.println("AttendanceManager: could not write attendance: " + e.getMessage());
        }
    }

    /**
     * Clears the attendance file (useful for testing).
     */
    public synchronized void resetAttendance() {
        try (PrintWriter pw = new PrintWriter(file)) {
            // overwrite with empty content
            pw.print("");
        } catch (IOException e) {
            System.err.println("AttendanceManager: could not reset attendance: " + e.getMessage());
        }
    }
}
