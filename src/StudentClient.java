import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;

/**
 * StudentClient:
 * - Asks for Name + UID before connecting.
 * - Connects to TeacherServer at port 5000.
 * - Default: private messages (sent to teacher only).
 * - If teacher enables group chat, server will start broadcasting; client shows group messages.
 * - Uses WhatsApp-like chat bubbles: sent (green/right), received (white/left).
 */
public class StudentClient {
    private final String SERVER_HOST = "localhost";
    private final int SERVER_PORT = 5000;

    private JFrame frame;
    private JPanel chatPanel;
    private JScrollPane scrollPane;
    private JTextField inputField;
    private JButton sendBtn, leaveBtn;
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;

    private String name;
    private String uid;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new StudentClient().start());
    }

    private void start() {
        // Prompt until valid name+uid and successful connect
        boolean connected = false;
        while (!connected) {
            JTextField nameField = new JTextField();
            JTextField uidField = new JTextField();
            Object[] form = {"Enter Name:", nameField, "Enter UID:", uidField};
            int res = JOptionPane.showConfirmDialog(null, form, "Join Class", JOptionPane.OK_CANCEL_OPTION);
            if (res != JOptionPane.OK_OPTION) {
                System.exit(0);
            }
            name = nameField.getText().trim();
            uid = uidField.getText().trim();

            if (name.isEmpty() || uid.isEmpty()) {
                JOptionPane.showMessageDialog(null, "Both Name and UID are required.");
                continue;
            }

            // attempt connect and register
            try {
                socket = new Socket(SERVER_HOST, SERVER_PORT);
                out = new PrintWriter(socket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                // send name and uid
                out.println(name);
                out.println(uid);

                // read immediate response (server may reply "UID_EXISTS" or other)
                // If server replies "UID_EXISTS", it will close
                socket.setSoTimeout(1500); // small timeout to wait for potential immediate reply
                try {
                    String immediate = in.readLine(); // may be null if no immediate reply
                    if (immediate != null && immediate.contains("UID_EXISTS")) {
                        JOptionPane.showMessageDialog(null, "UID already exists. Try again with a different UID.");
                        closeQuietly();
                        continue;
                    } else if (immediate != null && immediate.startsWith("⚠ Server stopped")) {
                        JOptionPane.showMessageDialog(null, "Server responded: " + immediate);
                        closeQuietly();
                        continue;
                    } else {
                        // no immediate error; put line back into stream? cannot un-read,
                        // but server typically doesn't send anything immediate (other than errors).
                    }
                } catch (IOException toe) {
                    // timeout - no immediate response, proceed
                } finally {
                    socket.setSoTimeout(0); // remove timeout
                }

                connected = true; // connected and accepted
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(null, "Could not connect to server: " + ex.getMessage());
            }
        }

        createAndShowChatUI();

        // start listening to server lines
        new Thread(this::listenLoop).start();
    }

    // Build chat GUI (WhatsApp-style bubbles)
    private void createAndShowChatUI() {
        frame = new JFrame("Student: " + name + " (" + uid + ")");
        frame.setSize(480, 640);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        chatPanel = new JPanel();
        chatPanel.setLayout(new BoxLayout(chatPanel, BoxLayout.Y_AXIS));
        chatPanel.setBackground(new Color(245,245,245));
        scrollPane = new JScrollPane(chatPanel);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        frame.add(scrollPane, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        inputField = new JTextField();
        sendBtn = new JButton("Send");
        leaveBtn = new JButton("Leave Class");

        sendBtn.setBackground(new Color(0,153,255));
        sendBtn.setForeground(Color.WHITE);
        leaveBtn.setBackground(new Color(255,77,77));
        leaveBtn.setForeground(Color.WHITE);

        bottom.add(inputField, BorderLayout.CENTER);
        JPanel rightBtns = new JPanel(new BorderLayout());
        rightBtns.add(sendBtn, BorderLayout.CENTER);
        rightBtns.add(leaveBtn, BorderLayout.EAST);
        bottom.add(rightBtns, BorderLayout.EAST);

        frame.add(bottom, BorderLayout.SOUTH);

        sendBtn.addActionListener(e -> sendMessage());
        inputField.addActionListener(e -> sendMessage());
        leaveBtn.addActionListener(e -> leaveClass());

        frame.setVisible(true);
    }

    // Listening loop: receive and display messages from server
    private void listenLoop() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                // show received messages on left side
                addMessage(line, false);
            }
        } catch (IOException e) {
            addMessage("⚠ Disconnected from server.", false);
        } finally {
            closeQuietly();
        }
    }

    // Send text typed by the student
    private void sendMessage() {
        String txt = inputField.getText().trim();
        if (txt.isEmpty()) return;
        // Show student message locally (right aligned)
        addMessage("You: " + txt, true);
        // send to server; server decides routing: teacher only or group broadcast
        out.println(txt);
        inputField.setText("");
    }

    // Leave class: notify server and close
    private void leaveClass() {
        try {
            // send leave command so server broadcasts leave message and cleans up
            out.println("/leave");
            closeQuietly();
            frame.dispose();
            System.exit(0);
        } catch (Exception ignored) {}
    }

    // Add WhatsApp-style bubble to chatPanel: isSender true -> right/green, else left/white
    private void addMessage(String message, boolean isSender) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        JPanel bubble = new JPanel(new BorderLayout());
        bubble.setBorder(new EmptyBorder(8,10,8,10));
        JLabel label = new JLabel("<html><p style='width:260px'>" + escapeHtml(message) + "</p></html>");
        label.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        label.setOpaque(true);
        if (isSender) {
            label.setBackground(new Color(200,245,200)); // green
            bubble.add(label, BorderLayout.EAST);
            wrapper.add(bubble, BorderLayout.EAST);
        } else {
            label.setBackground(Color.WHITE);
            bubble.add(label, BorderLayout.WEST);
            wrapper.add(bubble, BorderLayout.WEST);
        }
        chatPanel.add(wrapper);
        chatPanel.revalidate();
        SwingUtilities.invokeLater(() -> scrollPane.getVerticalScrollBar().setValue(scrollPane.getVerticalScrollBar().getMaximum()));
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<","&lt;").replace(">","&gt;");
    }

    private void closeQuietly() {
        try { if (out != null) out.close(); } catch (Exception ignored) {}
        try { if (in != null) in.close(); } catch (Exception ignored) {}
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (Exception ignored) {}
    }
}
