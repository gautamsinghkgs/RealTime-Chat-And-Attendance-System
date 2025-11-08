import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.util.*;

/**
 * TeacherServer
 * - GUI with Connected Students list
 * - View Attendance + Reset Attendance buttons
 * - Private chat by default; teacher-controlled group chat
 * - Case-insensitive UID uniqueness
 * - Attendance stored in memory and file
 */
public class TeacherServer {
    // GUI components
    private JFrame frame;
    private JPanel chatPanel;
    private JScrollPane chatScroll;
    private JTextField teacherMessageField;
    private JButton teacherSendBtn;
    private DefaultListModel<String> studentListModel;
    private JList<String> studentList;
    private JButton startServerBtn, stopServerBtn, startGroupBtn, viewAttendanceBtn, resetAttendanceBtn;

    // Networking
    private final int PORT = 5000;
    private ServerSocket serverSocket;
    private volatile boolean serverRunning = false;
    private final Map<String, ClientHandler> clients = Collections.synchronizedMap(new HashMap<>());
    private volatile boolean groupChatEnabled = false;

    // Attendance
    private final Set<String> attendanceSet = Collections.synchronizedSet(new LinkedHashSet<>());
    private final File attendanceFile = new File("attendance.txt");

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new TeacherServer().createAndShowGUI());
    }

    private void createAndShowGUI() {
        frame = new JFrame("Teacher Dashboard - Classroom Chat (Port " + PORT + ")");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(950, 650);
        frame.setLayout(new BorderLayout());

        // LEFT PANEL
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setPreferredSize(new Dimension(280, 0));
        leftPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        studentListModel = new DefaultListModel<>();
        studentList = new JList<>(studentListModel);
        studentList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        studentList.setFixedCellHeight(30);

        leftPanel.add(new JLabel("Connected Students:"), BorderLayout.NORTH);
        leftPanel.add(new JScrollPane(studentList), BorderLayout.CENTER);

        JPanel leftButtons = new JPanel(new GridLayout(0, 1, 6, 6));
        startServerBtn = new JButton("Start Server");
        stopServerBtn = new JButton("Stop Server");
        stopServerBtn.setEnabled(false);
        startGroupBtn = new JButton("Start Group Chat");
        startGroupBtn.setEnabled(false);
        viewAttendanceBtn = new JButton("📋 View Attendance");
        viewAttendanceBtn.setEnabled(false);
        resetAttendanceBtn = new JButton("🔄 Reset Attendance");
        resetAttendanceBtn.setEnabled(false);

        leftButtons.add(startServerBtn);
        leftButtons.add(stopServerBtn);
        leftButtons.add(startGroupBtn);
        leftButtons.add(viewAttendanceBtn);
        leftButtons.add(resetAttendanceBtn);
        leftPanel.add(leftButtons, BorderLayout.SOUTH);
        frame.add(leftPanel, BorderLayout.WEST);

        // RIGHT PANEL
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        chatPanel = new JPanel();
        chatPanel.setLayout(new BoxLayout(chatPanel, BoxLayout.Y_AXIS));
        chatPanel.setBackground(new Color(245, 245, 245));
        chatScroll = new JScrollPane(chatPanel);
        chatScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);

        JPanel inputPanel = new JPanel(new BorderLayout(6, 6));
        teacherMessageField = new JTextField();
        teacherSendBtn = new JButton("Send to Selected / Group");
        teacherSendBtn.setEnabled(false);
        inputPanel.add(teacherMessageField, BorderLayout.CENTER);
        inputPanel.add(teacherSendBtn, BorderLayout.EAST);

        rightPanel.add(chatScroll, BorderLayout.CENTER);
        rightPanel.add(inputPanel, BorderLayout.SOUTH);
        frame.add(rightPanel, BorderLayout.CENTER);

        // Action Listeners
        startServerBtn.addActionListener(e -> startServer());
        stopServerBtn.addActionListener(e -> stopServer());
        teacherSendBtn.addActionListener(e -> teacherSend());
        teacherMessageField.addActionListener(e -> teacherSend());
        startGroupBtn.addActionListener(e -> toggleGroupChat());
        viewAttendanceBtn.addActionListener(e -> showAttendancePopup());
        resetAttendanceBtn.addActionListener(e -> resetAttendance());

        studentList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent evt) {
                if (evt.getClickCount() == 2) {
                    String selected = studentList.getSelectedValue();
                    if (selected != null) {
                        String uid = extractUID(selected);
                        if (uid != null && clients.containsKey(uid)) {
                            openPrivateChatWindow(uid);
                        } else {
                            JOptionPane.showMessageDialog(frame, "Student not connected.");
                        }
                    }
                }
            }
        });

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private String extractUID(String display) {
        if (display == null) return null;
        int start = display.lastIndexOf('(');
        int end = display.lastIndexOf(')');
        if (start >= 0 && end > start) {
            return display.substring(start + 1, end).trim();
        }
        return null;
    }

    // ---------------- Server lifecycle ----------------
    private void startServer() {
        try {
            serverSocket = new ServerSocket(PORT);
            serverRunning = true;

            startServerBtn.setEnabled(false);
            stopServerBtn.setEnabled(true);
            startGroupBtn.setEnabled(true);
            teacherSendBtn.setEnabled(true);
            viewAttendanceBtn.setEnabled(true);
            resetAttendanceBtn.setEnabled(true);

            appendSystemMessage("✅ Server started on port " + PORT + ". Waiting for students...");

            Thread acceptThread = new Thread(() -> {
                while (serverRunning) {
                    try {
                        Socket s = serverSocket.accept();
                        ClientHandler ch = new ClientHandler(s);
                        ch.start();
                    } catch (IOException ex) {
                        if (serverRunning)
                            appendSystemMessage("⚠ Error accepting connection: " + ex.getMessage());
                    }
                }
            });
            acceptThread.setDaemon(true);
            acceptThread.start();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(frame, "Could not start server: " + ex.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void stopServer() {
        serverRunning = false;
        try {
            if (serverSocket != null)
                serverSocket.close();
        } catch (IOException ignored) {
        }

        synchronized (clients) {
            for (ClientHandler ch : new ArrayList<>(clients.values())) {
                ch.sendLine("⚠ Server stopped by teacher.");
                ch.closeQuietly();
            }
            clients.clear();
        }
        studentListModel.clear();
        attendanceSet.clear();
        appendSystemMessage("🛑 Server stopped. All clients disconnected.");
        startServerBtn.setEnabled(true);
        stopServerBtn.setEnabled(false);
        startGroupBtn.setEnabled(false);
        teacherSendBtn.setEnabled(false);
        viewAttendanceBtn.setEnabled(false);
        resetAttendanceBtn.setEnabled(false);
    }

    // ---------------- Teacher messaging ----------------
    private void teacherSend() {
        String text = teacherMessageField.getText().trim();
        if (text.isEmpty())
            return;

        if (groupChatEnabled) {
            appendMessage("👩‍🏫 Teacher: " + text, true);
            broadcastToAll("👩‍🏫 Teacher: " + text);
        } else {
            String sel = studentList.getSelectedValue();
            if (sel == null) {
                JOptionPane.showMessageDialog(frame, "Select a student to send private message.");
                return;
            }
            String uid = extractUID(sel);
            ClientHandler ch = clients.get(uid);
            if (ch != null) {
                appendMessage("To " + sel + ": " + text, true);
                ch.sendLine("👩‍🏫 Teacher (private): " + text);
            } else {
                appendSystemMessage("Student not connected: " + sel);
            }
        }
        teacherMessageField.setText("");
    }

    private void toggleGroupChat() {
        groupChatEnabled = !groupChatEnabled;
        if (groupChatEnabled) {
            startGroupBtn.setText("Stop Group Chat");
            appendSystemMessage("💬 Group Chat ENABLED.");
        } else {
            startGroupBtn.setText("Start Group Chat");
            appendSystemMessage("🔕 Group Chat DISABLED.");
        }
    }

    // ---------------- Attendance popup & reset ----------------
    private void showAttendancePopup() {
        if (attendanceSet.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "No students are marked present yet.", "Attendance",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        area.append("📋 Attendance List (Present):\n\n");
        synchronized (attendanceSet) {
            for (String s : attendanceSet) {
                area.append(s + "\n");
            }
        }
        JScrollPane sp = new JScrollPane(area);
        sp.setPreferredSize(new Dimension(380, 300));
        JOptionPane.showMessageDialog(frame, sp, "Attendance", JOptionPane.INFORMATION_MESSAGE);
    }

    private void resetAttendance() {
        int confirm = JOptionPane.showConfirmDialog(frame, "Are you sure you want to reset attendance?",
                "Confirm Reset", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            attendanceSet.clear();
            if (attendanceFile.exists())
                attendanceFile.delete();
            broadcastToAll("🔄 Attendance list has been reset by the teacher.");
            appendSystemMessage("🔄 Attendance list has been reset.");
            JOptionPane.showMessageDialog(frame, "Attendance reset successfully!");
        }
    }

    // ---------------- Private Chat Window ----------------
    private void openPrivateChatWindow(String targetUid) {
        ClientHandler ch = clients.get(targetUid);
        if (ch == null) {
            JOptionPane.showMessageDialog(frame, "Student disconnected.");
            return;
        }

        JDialog dialog = new JDialog(frame, "Private Chat with " + ch.name + " (" + ch.uid + ")", false);
        dialog.setSize(420, 420);
        dialog.setLayout(new BorderLayout());

        JPanel pChat = new JPanel();
        pChat.setLayout(new BoxLayout(pChat, BoxLayout.Y_AXIS));
        pChat.setBackground(new Color(245, 245, 245));
        JScrollPane sp = new JScrollPane(pChat);

        JTextField input = new JTextField();
        JButton send = new JButton("Send");

        dialog.add(sp, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(input, BorderLayout.CENTER);
        bottom.add(send, BorderLayout.EAST);
        dialog.add(bottom, BorderLayout.SOUTH);

        send.addActionListener(ev -> {
            String txt = input.getText().trim();
            if (txt.isEmpty())
                return;
            JPanel bubble = createBubble("You: " + txt, true);
            pChat.add(bubble);
            pChat.revalidate();
            ch.sendLine("👩‍🏫 Teacher (private): " + txt);
            input.setText("");
            SwingUtilities.invokeLater(
                    () -> sp.getVerticalScrollBar().setValue(sp.getVerticalScrollBar().getMaximum()));
        });

        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    // ---------------- UI Helpers ----------------
    private void appendMessage(String text, boolean isSender) {
        JPanel bubble = createBubble(text, isSender);
        chatPanel.add(Box.createVerticalStrut(6));
        chatPanel.add(bubble);
        chatPanel.revalidate();
        SwingUtilities.invokeLater(
                () -> chatScroll.getVerticalScrollBar().setValue(chatScroll.getVerticalScrollBar().getMaximum()));
    }

    private void appendSystemMessage(String msg) {
        appendMessage("[SYSTEM] " + msg, false);
    }

    private void broadcastToAll(String msg) {
        synchronized (clients) {
            for (ClientHandler ch : clients.values()) {
                ch.sendLine(msg);
            }
        }
    }

    private JPanel createBubble(String message, boolean isSender) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        JLabel label = new JLabel("<html><p style='width:300px'>" + escapeHtml(message) + "</p></html>");
        label.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        label.setOpaque(true);
        label.setBorder(new EmptyBorder(8, 12, 8, 12));
        if (isSender) {
            label.setBackground(new Color(200, 245, 200));
            wrapper.add(label, BorderLayout.EAST);
        } else {
            label.setBackground(Color.WHITE);
            wrapper.add(label, BorderLayout.WEST);
        }
        return wrapper;
    }

    private String escapeHtml(String s) {
        if (s == null)
            return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------------- ClientHandler ----------------
    private class ClientHandler extends Thread {
        private final Socket socket;
        private PrintWriter out;
        private BufferedReader in;
        private String name;
        private String uid;

        ClientHandler(Socket s) {
            this.socket = s;
        }

        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);

                name = in.readLine();
                uid = in.readLine();

                if (name == null || uid == null) {
                    closeQuietly();
                    return;
                }

                String uidKey = uid.trim().toUpperCase();

                synchronized (clients) {
                    if (clients.containsKey(uidKey)) {
                        out.println("UID_EXISTS");
                        closeQuietly();
                        return;
                    }
                    this.uid = uidKey;
                    clients.put(uidKey, this);
                }

                String display = name + " (" + uidKey + ")";
                SwingUtilities.invokeLater(() -> studentListModel.addElement(display));
                attendanceSet.add(display);

                appendMessage("👨‍🎓 " + display + " joined.", false);
                broadcastToAll("📘 " + display + " marked as PRESENT.");
                out.println("📘 You are marked as PRESENT in today's attendance.");

                String line;
                while ((line = in.readLine()) != null) {
                    if (line.trim().isEmpty())
                        continue;
                    if ("/leave".equalsIgnoreCase(line.trim()))
                        break;

                    if (groupChatEnabled) {
                        broadcastToAll(display + ": " + line);
                        appendMessage(display + ": " + line, false);
                    } else {
                        appendMessage(display + ": " + line, false);
                    }
                }

                removeClient(uidKey, display, name);

            } catch (IOException e) {
                if (uid != null) {
                    removeClient(uid, name + " (" + uid + ")", name);
                }
            }
        }

        public void sendLine(String msg) {
            if (out != null)
                out.println(msg);
        }

        private void closeQuietly() {
            try {
                if (socket != null && !socket.isClosed())
                    socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void removeClient(String uidKey, String display, String nameOnly) {
        clients.remove(uidKey);
        SwingUtilities.invokeLater(() -> studentListModel.removeElement(display));
        appendMessage("⚠ " + display + " left the class.", false);
        broadcastToAll("⚠ " + display + " left the class.");
    }
}
