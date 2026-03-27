import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.*;
import java.net.*;
import java.text.*;
import java.util.*;
import java.util.List;

public class client2 {

    // ─── Palette ─────────────────────────────────────────────────────────────
    private static final Color C_BG      = new Color(0xEFF2F7);
    private static final Color C_HDR1    = new Color(0x1e1b4b);
    private static final Color C_HDR2    = new Color(0x4338ca);
    private static final Color C_SENT1   = new Color(0x4f46e5);
    private static final Color C_SENT2   = new Color(0x7c3aed);
    private static final Color C_RECV    = Color.WHITE;
    private static final Color C_NOTIF   = new Color(0x6b7280);
    private static final Color C_ACCENT  = new Color(0x4f46e5);
    private static final Color C_GREEN   = new Color(0x10b981);
    private static final Color C_RED     = new Color(0xef4444);
    private static final Color C_SIDEBAR = new Color(0xfafafa);
    private static final Color C_TEXT1   = new Color(0x111827);
    private static final Color C_TEXT2   = new Color(0x6b7280);
    private static final Color C_DIVIDER = new Color(0xe5e7eb);

    private static final String[] EMOJIS = {
            "👍","👎","❤️","😂","😮","😢","😡","🎉","🔥","💯",
            "✅","🙏","😍","🤔","👏","😎","🤣","💪","🫡","⭐"
    };

    // ─── State ───────────────────────────────────────────────────────────────
    private JFrame frame;
    private JPanel chatPanel;
    private JScrollPane chatScroll;
    private JTextField messageField;
    private JLabel userCountLabel, typingLabel, topicLabel, roomTitleLabel;
    private JPanel sidebarPanel;
    private DefaultListModel<UserEntry> userListModel;
    private JList<UserEntry> userList;

    private PrintWriter out;
    private BufferedReader in;
    private Socket socket;

    private String username, roomCode, roomName, adminName, roomTopic = "";
    private boolean awaitingHistory = false;

    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm");
    private final Map<String, MessageData> messagesById = new LinkedHashMap<>();

    private javax.swing.Timer typingStopTimer;
    private boolean typingSent = false;
    private final Set<String> typers = new LinkedHashSet<>();

    // ─── Inner types ─────────────────────────────────────────────────────────

    private static class UserEntry {
        String username; boolean muted, admin;
        UserEntry(String u, boolean m, boolean a) { username = u; muted = m; admin = a; }
        public String toString() { return username; }
    }

    private static class MessageData {
        String msgId, sender, text;
        boolean isMine;
        JTextArea textArea;   // replaces JLabel for proper word-wrap
        JPanel reactPanel, rowWrapper;
        JLabel tickLabel, timeLabel;
        final Map<String, Set<String>> reactions = new LinkedHashMap<>();
        MessageData(String i, String s, String t, boolean mine) {
            msgId = i; sender = s; text = t; isMine = mine;
        }
    }

    // ─── Bubble panel ────────────────────────────────────────────────────────

    private static class BubblePanel extends JPanel {
        private final boolean sent;
        BubblePanel(boolean sent) { this.sent = sent; setOpaque(false); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight(), arc = 18;
            if (sent) {
                GradientPaint gp = new GradientPaint(0, 0, C_SENT1, w, h, C_SENT2);
                g2.setPaint(gp);
                g2.fillRoundRect(0, 0, w, h, arc, arc);
            } else {
                // Soft shadow
                g2.setColor(new Color(0, 0, 0, 14));
                g2.fillRoundRect(2, 3, w - 2, h - 2, arc, arc);
                g2.setColor(Color.WHITE);
                g2.fillRoundRect(0, 0, w - 2, h - 3, arc, arc);
            }
            g2.dispose();
        }
    }

    // ─── Gradient header panel ────────────────────────────────────────────────

    private static class GradientPanel extends JPanel {
        GradientPanel() { setOpaque(false); }
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            GradientPaint gp = new GradientPaint(0, 0, C_HDR1, getWidth(), 0, C_HDR2);
            g2.setPaint(gp);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.dispose();
        }
    }

    // ─── Entry ───────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(client::new);
    }

    public client2() {
        connectToServer();
        setupUI();
        startListening();
        setupTypingLogic();
    }

    private boolean isAdmin() { return username != null && username.equals(adminName); }

    // ─── UI construction ──────────────────────────────────────────────────────

    private void setupUI() {
        frame = new JFrame("ChatSpace — " + roomName);
        frame.setSize(960, 760);
        frame.setMinimumSize(new Dimension(720, 540));
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.getContentPane().setBackground(C_BG);
        frame.setLayout(new BorderLayout());

        frame.add(buildHeader(), BorderLayout.NORTH);

        // Main area: sidebar + chat
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildSidebar(), buildChatArea());
        split.setDividerLocation(190);
        split.setDividerSize(1);
        split.setBorder(null);
        split.setBackground(C_DIVIDER);
        frame.add(split, BorderLayout.CENTER);

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    // ── Header ───────────────────────────────────────────────────────────────

    private JPanel buildHeader() {
        GradientPanel header = new GradientPanel();
        header.setLayout(new BorderLayout(14, 0));
        header.setBorder(new EmptyBorder(12, 20, 12, 20));

        // Left: title block
        JPanel titleBlock = new JPanel();
        titleBlock.setLayout(new BoxLayout(titleBlock, BoxLayout.Y_AXIS));
        titleBlock.setOpaque(false);

        roomTitleLabel = new JLabel(roomName);
        roomTitleLabel.setFont(new Font("SansSerif", Font.BOLD, 20));
        roomTitleLabel.setForeground(Color.WHITE);

        JLabel adminLabel = new JLabel("Admin: " + adminName + "  ·  🔑 " + roomCode);
        adminLabel.setFont(new Font("Monospaced", Font.PLAIN, 11));
        adminLabel.setForeground(new Color(0xc4b5fd));

        topicLabel = new JLabel(roomTopic.isEmpty() ? "No topic set" : roomTopic);
        topicLabel.setFont(new Font("SansSerif", Font.ITALIC, 11));
        topicLabel.setForeground(new Color(0xa5b4fc));

        typingLabel = new JLabel(" ");
        typingLabel.setFont(new Font("SansSerif", Font.ITALIC, 11));
        typingLabel.setForeground(new Color(0xd1fae5));

        titleBlock.add(roomTitleLabel);
        titleBlock.add(Box.createVerticalStrut(2));
        titleBlock.add(adminLabel);
        titleBlock.add(topicLabel);
        titleBlock.add(typingLabel);

        // Right: online count + actions
        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        rightPanel.setOpaque(false);

        userCountLabel = makeHeaderLabel("● 1 Online", C_GREEN);
        userCountLabel.setCursor(new Cursor(Cursor.HAND_CURSOR));

        JButton usersBtn  = makeHeaderButton("👥 Users");
        JButton topicBtn  = makeHeaderButton("✏️ Topic");
        JButton endBtn    = makeHeaderButton(isAdmin() ? "🚫 End" : "🚪 Leave");

        endBtn.setForeground(new Color(0xfca5a5));
        if (!isAdmin()) topicBtn.setEnabled(false);

        rightPanel.add(userCountLabel);
        rightPanel.add(usersBtn);
        if (isAdmin()) rightPanel.add(topicBtn);
        rightPanel.add(endBtn);

        header.add(titleBlock, BorderLayout.WEST);
        header.add(rightPanel, BorderLayout.EAST);

        // Actions
        usersBtn.addActionListener(e -> out.println("LIST"));
        endBtn.addActionListener(e -> {
            if (isAdmin()) {
                if (JOptionPane.showConfirmDialog(frame, "End the room for everyone?",
                        "Confirm", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
                    out.println("END");
            } else {
                try { socket.close(); } catch (IOException ignored) {}
                System.exit(0);
            }
        });
        if (isAdmin()) topicBtn.addActionListener(e -> {
            String t = JOptionPane.showInputDialog(frame, "Set room topic:", roomTopic);
            if (t != null) out.println("SETTOPIC:" + t.trim());
        });
        userCountLabel.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { out.println("LIST"); }
        });

        return header;
    }

    // ── Sidebar ───────────────────────────────────────────────────────────────

    private JPanel buildSidebar() {
        sidebarPanel = new JPanel(new BorderLayout());
        sidebarPanel.setBackground(C_SIDEBAR);
        sidebarPanel.setBorder(new MatteBorder(0, 0, 0, 1, C_DIVIDER));
        sidebarPanel.setPreferredSize(new Dimension(190, 0));

        JLabel title = new JLabel("Online Users");
        title.setFont(new Font("SansSerif", Font.BOLD, 12));
        title.setForeground(C_TEXT2);
        title.setBorder(new EmptyBorder(14, 14, 10, 14));

        userListModel = new DefaultListModel<>();
        userListModel.addElement(new UserEntry(username, false, isAdmin()));

        userList = new JList<>(userListModel);
        userList.setBackground(C_SIDEBAR);
        userList.setFixedCellHeight(44);
        userList.setCellRenderer(new UserCellRenderer());
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userList.setBorder(new EmptyBorder(0, 0, 0, 0));

        // Double-click to DM
        userList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    UserEntry ue = userList.getSelectedValue();
                    if (ue != null && !ue.username.equals(username)) showDmDialog(ue.username);
                }
                if (SwingUtilities.isRightMouseButton(e) && isAdmin()) {
                    userList.setSelectedIndex(userList.locationToIndex(e.getPoint()));
                    UserEntry ue = userList.getSelectedValue();
                    if (ue != null && !ue.username.equals(adminName)) showUserContextMenu(ue, e.getComponent(), e.getX(), e.getY());
                }
            }
        });

        JScrollPane sp = new JScrollPane(userList);
        sp.setBorder(null);
        sp.setBackground(C_SIDEBAR);

        JLabel hint = new JLabel("Double-click to DM");
        hint.setFont(new Font("SansSerif", Font.PLAIN, 10));
        hint.setForeground(C_TEXT2);
        hint.setHorizontalAlignment(SwingConstants.CENTER);
        hint.setBorder(new EmptyBorder(6, 0, 10, 0));

        sidebarPanel.add(title, BorderLayout.NORTH);
        sidebarPanel.add(sp,    BorderLayout.CENTER);
        sidebarPanel.add(hint,  BorderLayout.SOUTH);
        return sidebarPanel;
    }

    // ── Chat area + input ─────────────────────────────────────────────────────

    private JPanel buildChatArea() {
        JPanel chatArea = new JPanel(new BorderLayout());
        chatArea.setBackground(C_BG);

        chatPanel = new JPanel();
        chatPanel.setLayout(new BoxLayout(chatPanel, BoxLayout.Y_AXIS));
        chatPanel.setBackground(C_BG);
        chatPanel.setBorder(new EmptyBorder(12, 8, 12, 8));

        chatScroll = new JScrollPane(chatPanel);
        chatScroll.setBorder(null);
        chatScroll.getVerticalScrollBar().setUnitIncrement(16);
        chatScroll.getViewport().setBackground(C_BG);

        chatArea.add(chatScroll, BorderLayout.CENTER);
        chatArea.add(buildInputBar(), BorderLayout.SOUTH);
        return chatArea;
    }

    private JPanel buildInputBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBackground(Color.WHITE);
        bar.setBorder(new CompoundBorder(
                new MatteBorder(1, 0, 0, 0, C_DIVIDER),
                new EmptyBorder(12, 16, 12, 16)
        ));

        // Emoji button
        JButton emojiBtn = new JButton("😊");
        emojiBtn.setFont(new Font("SansSerif", Font.PLAIN, 18));
        emojiBtn.setFocusPainted(false);
        emojiBtn.setBorderPainted(false);
        emojiBtn.setContentAreaFilled(false);
        emojiBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        emojiBtn.setToolTipText("Insert emoji");

        // Message field with rounded border
        messageField = new JTextField();
        messageField.setFont(new Font("SansSerif", Font.PLAIN, 14));
        messageField.setBorder(new CompoundBorder(
                new LineBorder(C_DIVIDER, 1, true),
                new EmptyBorder(9, 14, 9, 14)
        ));
        messageField.setBackground(C_BG);

        // Send button
        JButton sendBtn = new JButton("Send ›") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                GradientPaint gp = new GradientPaint(0, 0, C_SENT1, getWidth(), getHeight(), C_SENT2);
                g2.setPaint(gp);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        sendBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
        sendBtn.setForeground(Color.WHITE);
        sendBtn.setFocusPainted(false);
        sendBtn.setBorderPainted(false);
        sendBtn.setContentAreaFilled(false);
        sendBtn.setOpaque(false);
        sendBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        sendBtn.setBorder(new EmptyBorder(9, 20, 9, 20));

        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        leftButtons.setOpaque(false);
        leftButtons.add(emojiBtn);

        bar.add(leftButtons,    BorderLayout.WEST);
        bar.add(messageField,   BorderLayout.CENTER);
        bar.add(sendBtn,        BorderLayout.EAST);

        sendBtn.addActionListener(e -> sendMessage());
        messageField.addActionListener(e -> sendMessage());
        emojiBtn.addActionListener(e -> showEmojiInsertPicker(emojiBtn));

        return bar;
    }

    // ─── Message rendering ────────────────────────────────────────────────────

    private void addHistoryDivider() {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(6, 20, 6, 20));

        JSeparator left  = new JSeparator(); left.setForeground(C_DIVIDER);
        JSeparator right = new JSeparator(); right.setForeground(C_DIVIDER);
        JLabel lbl = new JLabel("  Chat History  ");
        lbl.setFont(new Font("SansSerif", Font.PLAIN, 10));
        lbl.setForeground(C_TEXT2);

        row.add(left, BorderLayout.WEST);
        row.add(lbl, BorderLayout.CENTER);
        row.add(right, BorderLayout.EAST);

        JPanel wrapper = new JPanel();
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
        wrapper.setOpaque(false);
        wrapper.add(row);
        wrapper.add(Box.createVerticalStrut(4));
        chatPanel.add(wrapper);
    }

    private void addNotification(String message) {
        JPanel center = new JPanel(new FlowLayout(FlowLayout.CENTER));
        center.setOpaque(false);

        JLabel lbl = new JLabel(message);
        lbl.setFont(new Font("SansSerif", Font.PLAIN, 11));
        lbl.setForeground(Color.WHITE);
        lbl.setBackground(new Color(0, 0, 0, 60));
        lbl.setOpaque(true);
        lbl.setBorder(new EmptyBorder(4, 12, 4, 12));
        center.add(lbl);

        JPanel wrapper = new JPanel();
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
        wrapper.setOpaque(false);
        wrapper.add(center);
        wrapper.add(Box.createVerticalStrut(4));
        chatPanel.add(wrapper);
        scrollToBottom();
    }

    private static final int BUBBLE_MAX_W = 340;

    private MessageData addMessageBubble(String msgId, String sender, String text, long ts, boolean isMine) {
        MessageData data = new MessageData(msgId, sender, text, isMine);

        // ── Bubble panel: cap width so it never fills full container ──────────
        BubblePanel bubble = new BubblePanel(isMine) {
            @Override public Dimension getMaximumSize() {
                return new Dimension(BUBBLE_MAX_W, super.getMaximumSize().height);
            }
            @Override public Dimension getMinimumSize() { return getPreferredSize(); }
        };
        bubble.setLayout(new BoxLayout(bubble, BoxLayout.Y_AXIS));
        bubble.setBorder(new EmptyBorder(10, 14, 8, 14));

        // Sender name (incoming only)
        if (!isMine) {
            JLabel senderLbl = new JLabel(sender);
            senderLbl.setFont(new Font("SansSerif", Font.BOLD, 11));
            senderLbl.setForeground(colorFromName(sender));
            senderLbl.setAlignmentX(0f);
            bubble.add(senderLbl);
            bubble.add(Box.createVerticalStrut(3));
        }

        // ── JTextArea: line-wraps naturally, opaque-false so bubble paints through
        JTextArea textArea = buildTextArea(text, isMine);
        bubble.add(textArea);
        data.textArea = textArea;

        // Reactions panel
        JPanel reactPanel = new JPanel(new WrapLayout(FlowLayout.LEFT, 3, 2));
        reactPanel.setOpaque(false);
        reactPanel.setAlignmentX(0f);
        reactPanel.setMaximumSize(new Dimension(BUBBLE_MAX_W, 200));
        bubble.add(reactPanel);
        data.reactPanel = reactPanel;

        // Meta row: time + ticks
        JPanel meta = new JPanel(new FlowLayout(isMine ? FlowLayout.RIGHT : FlowLayout.LEFT, 4, 0));
        meta.setOpaque(false);
        meta.setAlignmentX(0f);

        JLabel timeLabel = new JLabel(ts > 0 ? timeFmt.format(new Date(ts)) : " ");
        timeLabel.setFont(new Font("SansSerif", Font.PLAIN, 9));
        timeLabel.setForeground(isMine ? new Color(0xc4b5fd) : C_TEXT2);
        meta.add(timeLabel);
        data.timeLabel = timeLabel;

        if (isMine) {
            JLabel tick = new JLabel("✓");
            tick.setFont(new Font("SansSerif", Font.BOLD, 10));
            tick.setForeground(new Color(0xc4b5fd));
            meta.add(tick);
            data.tickLabel = tick;
        }
        bubble.add(meta);

        // Context menus
        MouseAdapter ctx = new MouseAdapter() {
            public void mousePressed(MouseEvent e)  { if (e.isPopupTrigger()) showMsgContextMenu(data, e); }
            public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) showMsgContextMenu(data, e); }
        };
        bubble.addMouseListener(ctx);
        textArea.addMouseListener(ctx);

        // ── Row: FlowLayout aligns bubble left or right at preferred size ──────
        JPanel row = new JPanel(new FlowLayout(isMine ? FlowLayout.RIGHT : FlowLayout.LEFT, 8, 2));
        row.setOpaque(false);
        // Tell BoxLayout the row height is preferred; width fills (that's fine – FlowLayout handles alignment)
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height + 60));

        if (!isMine) row.add(makeAvatar(sender));
        row.add(bubble);

        // ── Thin spacer wrapper ───────────────────────────────────────────────
        JPanel wrapper = new JPanel();
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
        wrapper.setOpaque(false);
        wrapper.add(row);
        wrapper.add(Box.createRigidArea(new Dimension(0, 6)));
        chatPanel.add(wrapper);
        data.rowWrapper = wrapper;

        messagesById.put(msgId, data);
        scrollToBottom();
        return data;
    }

    /** Non-editable JTextArea that word-wraps at BUBBLE_MAX_W and is transparent. */
    private JTextArea buildTextArea(String text, boolean isMine) {
        JTextArea ta = new JTextArea(text) {
            // Transparent – the BubblePanel paints the background
            @Override public boolean isOpaque() { return false; }
            @Override public Dimension getMaximumSize() {
                return new Dimension(BUBBLE_MAX_W - 28, super.getMaximumSize().height);
            }
        };
        ta.setFont(new Font("SansSerif", Font.PLAIN, 13));
        ta.setForeground(isMine ? Color.WHITE : C_TEXT1);
        ta.setEditable(false);
        ta.setFocusable(false);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        // setColumns drives preferred width before text is laid out
        ta.setColumns(24);
        ta.setBackground(new Color(0, 0, 0, 0));
        ta.setBorder(null);
        ta.setAlignmentX(0f);
        return ta;
    }

    private void ackMessage(String msgId, long ts) {
        MessageData d = messagesById.get(msgId);
        if (d == null) return;
        if (d.tickLabel != null) d.tickLabel.setText("✓✓");
        if (d.timeLabel != null) d.timeLabel.setText(timeFmt.format(new Date(ts)));
    }

    private void applyEdit(String msgId, String newText) {
        MessageData d = messagesById.get(msgId);
        if (d == null) return;
        d.text = newText;
        d.textArea.setText(newText + " (edited)");
        chatPanel.revalidate();
        chatPanel.repaint();
    }

    private void deleteMessageUI(String msgId) {
        MessageData d = messagesById.remove(msgId);
        if (d == null) return;
        chatPanel.remove(d.rowWrapper);
        chatPanel.revalidate();
        chatPanel.repaint();
    }

    private void addReaction(String msgId, String emoji, String by) {
        MessageData d = messagesById.get(msgId);
        if (d == null) return;
        d.reactions.computeIfAbsent(emoji, k -> new LinkedHashSet<>()).add(by);
        rebuildReactPanel(d);
    }

    private void rebuildReactPanel(MessageData d) {
        d.reactPanel.removeAll();
        for (Map.Entry<String, Set<String>> e : d.reactions.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            String emoji = e.getKey();
            int count = e.getValue().size();
            boolean iMine = e.getValue().contains(username);
            d.reactPanel.add(makeReactionChip(emoji, count, d.msgId, iMine));
        }
        d.reactPanel.revalidate();
        d.reactPanel.repaint();
        chatPanel.revalidate();
        chatPanel.repaint();
    }

    private JLabel makeReactionChip(String emoji, int count, String msgId, boolean iMine) {
        Color bg = iMine ? new Color(0x4f46e5, false) : new Color(0, 0, 0, 20);
        JLabel chip = new JLabel(emoji + " " + count) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(iMine ? new Color(99, 91, 255, 120) : new Color(0, 0, 0, 22));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        chip.setOpaque(false);
        chip.setFont(new Font("SansSerif", Font.PLAIN, 11));
        chip.setForeground(iMine ? new Color(0xc7d2fe) : C_TEXT1);
        chip.setBorder(new EmptyBorder(3, 7, 3, 7));
        chip.setCursor(new Cursor(Cursor.HAND_CURSOR));
        chip.setToolTipText("Click to react with " + emoji);
        chip.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { out.println("REACT:" + msgId + ":" + emoji + ":" + username); }
        });
        return chip;
    }

    // ─── Context menus ────────────────────────────────────────────────────────

    private void showMsgContextMenu(MessageData d, MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();
        stylePopup(menu);

        // React submenu
        JMenu reactMenu = new JMenu("React");
        for (String emoji : new String[]{"👍","👎","❤️","😂","😮","😢","🎉","🔥"}) {
            JMenuItem item = new JMenuItem(emoji);
            item.addActionListener(ev -> out.println("REACT:" + d.msgId + ":" + emoji + ":" + username));
            reactMenu.add(item);
        }
        menu.add(reactMenu);
        menu.addSeparator();

        JMenuItem copy = new JMenuItem("📋 Copy text");
        copy.addActionListener(ev -> {
            java.awt.datatransfer.StringSelection ss = new java.awt.datatransfer.StringSelection(d.text);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(ss, null);
        });
        menu.add(copy);

        if (d.isMine) {
            JMenuItem edit = new JMenuItem("✏️ Edit");
            edit.addActionListener(ev -> {
                String newText = (String) JOptionPane.showInputDialog(frame, "Edit message:", "Edit",
                        JOptionPane.PLAIN_MESSAGE, null, null, d.text);
                if (newText != null && !newText.trim().isEmpty())
                    out.println("EDIT:" + d.msgId + ":" + username + ":" + newText.trim());
            });
            menu.add(edit);
        }

        if (d.isMine || isAdmin()) {
            JMenuItem del = new JMenuItem("🗑 Delete");
            del.setForeground(C_RED);
            del.addActionListener(ev -> {
                if (JOptionPane.showConfirmDialog(frame, "Delete this message?", "Confirm",
                        JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
                    out.println("DELETE:" + d.msgId + ":" + username);
            });
            menu.add(del);
        }

        if (!d.isMine) {
            menu.addSeparator();
            JMenuItem dm = new JMenuItem("💬 DM " + d.sender);
            dm.addActionListener(ev -> showDmDialog(d.sender));
            menu.add(dm);
        }

        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    private void showUserContextMenu(UserEntry ue, Component c, int x, int y) {
        JPopupMenu menu = new JPopupMenu();
        stylePopup(menu);

        JMenuItem dm = new JMenuItem("💬 DM " + ue.username);
        dm.addActionListener(e -> showDmDialog(ue.username));
        menu.add(dm);
        menu.addSeparator();

        if (ue.muted) {
            JMenuItem unmute = new JMenuItem("🔊 Unmute");
            unmute.addActionListener(e -> { out.println("UNMUTE:" + ue.username); out.println("LIST"); });
            menu.add(unmute);
        } else {
            JMenuItem mute = new JMenuItem("🔇 Mute");
            mute.addActionListener(e -> { out.println("MUTE:" + ue.username); out.println("LIST"); });
            menu.add(mute);
        }

        JMenuItem kick = new JMenuItem("🚫 Kick");
        kick.setForeground(C_RED);
        kick.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(frame, "Kick " + ue.username + "?", "Confirm",
                    JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
                out.println("KICK:" + ue.username);
        });
        menu.add(kick);
        menu.show(c, x, y);
    }

    // ─── Emoji insert picker ─────────────────────────────────────────────────

    private void showEmojiInsertPicker(Component parent) {
        JPopupMenu popup = new JPopupMenu();
        JPanel grid = new JPanel(new GridLayout(4, 5, 2, 2));
        grid.setBorder(new EmptyBorder(6, 6, 6, 6));
        grid.setBackground(Color.WHITE);
        for (String em : EMOJIS) {
            JButton btn = new JButton(em);
            btn.setFont(new Font("SansSerif", Font.PLAIN, 17));
            btn.setFocusPainted(false);
            btn.setBorderPainted(false);
            btn.setContentAreaFilled(false);
            btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
            btn.addActionListener(e -> {
                messageField.setText(messageField.getText() + em);
                messageField.requestFocus();
                popup.setVisible(false);
            });
            grid.add(btn);
        }
        popup.add(grid);
        popup.show(parent, 0, -grid.getPreferredSize().height - 8);
    }

    // ─── DM dialog ────────────────────────────────────────────────────────────

    private void showDmDialog(String toUser) {
        JTextField dmField = new JTextField(28);
        dmField.setFont(new Font("SansSerif", Font.PLAIN, 13));
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.add(new JLabel("Message to " + toUser + ":"), BorderLayout.NORTH);
        panel.add(dmField, BorderLayout.CENTER);
        int res = JOptionPane.showConfirmDialog(frame, panel, "Direct Message", JOptionPane.OK_CANCEL_OPTION);
        if (res == JOptionPane.OK_OPTION && !dmField.getText().trim().isEmpty())
            out.println("DM:" + toUser + ":" + dmField.getText().trim());
    }

    // ─── Admin user panel ─────────────────────────────────────────────────────

    private void showAdminUsersDialog(String payload) {
        String[] parts = payload.isEmpty() ? new String[0] : payload.split("\\|");

        DefaultListModel<UserEntry> model = new DefaultListModel<>();
        for (String p : parts) {
            String[] f = p.split(":", 3);
            if (f.length >= 3)
                model.addElement(new UserEntry(f[0], "1".equals(f[1]), "1".equals(f[2])));
            else if (f.length > 0)
                model.addElement(new UserEntry(f[0], false, false));
        }

        JList<UserEntry> list = new JList<>(model);
        list.setCellRenderer(new UserCellRenderer());
        list.setFixedCellHeight(42);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(280, 220));

        JButton muteBtn   = new JButton("🔇 Mute");
        JButton unmuteBtn = new JButton("🔊 Unmute");
        JButton kickBtn   = new JButton("🚫 Kick");
        kickBtn.setForeground(C_RED);

        muteBtn.setEnabled(false); unmuteBtn.setEnabled(false); kickBtn.setEnabled(false);

        list.addListSelectionListener(e -> {
            UserEntry sel = list.getSelectedValue();
            boolean ok = sel != null && !sel.username.equals(adminName);
            muteBtn.setEnabled(ok && !sel.muted);
            unmuteBtn.setEnabled(ok && sel.muted);
            kickBtn.setEnabled(ok);
        });

        JDialog dlg = new JDialog(frame, "Manage Users", true);
        dlg.setLayout(new BorderLayout(10, 10));
        dlg.getRootPane().setBorder(new EmptyBorder(14, 14, 14, 14));

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btns.add(muteBtn); btns.add(unmuteBtn); btns.add(kickBtn);

        muteBtn.addActionListener(e -> {
            UserEntry sel = list.getSelectedValue();
            if (sel != null) { out.println("MUTE:" + sel.username); dlg.dispose(); out.println("LIST"); }
        });
        unmuteBtn.addActionListener(e -> {
            UserEntry sel = list.getSelectedValue();
            if (sel != null) { out.println("UNMUTE:" + sel.username); dlg.dispose(); out.println("LIST"); }
        });
        kickBtn.addActionListener(e -> {
            UserEntry sel = list.getSelectedValue();
            if (sel != null && JOptionPane.showConfirmDialog(dlg, "Kick " + sel.username + "?",
                    "Confirm", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                out.println("KICK:" + sel.username);
                dlg.dispose();
            }
        });

        dlg.add(new JLabel("  Online users — click to select, then act:"), BorderLayout.NORTH);
        dlg.add(sp, BorderLayout.CENTER);
        dlg.add(btns, BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);
    }

    // ─── Typing & input logic ─────────────────────────────────────────────────

    private void setupTypingLogic() {
        typingStopTimer = new javax.swing.Timer(900, e -> {
            if (typingSent) { out.println("TYPING:" + username + ":0"); typingSent = false; }
        });
        typingStopTimer.setRepeats(false);

        messageField.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() {
                String txt = messageField.getText();
                if (txt != null && !txt.isEmpty()) {
                    if (!typingSent) { out.println("TYPING:" + username + ":1"); typingSent = true; }
                    typingStopTimer.restart();
                } else {
                    if (typingSent) { out.println("TYPING:" + username + ":0"); typingSent = false; }
                    typingStopTimer.stop();
                }
            }
            public void insertUpdate(DocumentEvent e)  { changed(); }
            public void removeUpdate(DocumentEvent e)  { changed(); }
            public void changedUpdate(DocumentEvent e) { changed(); }
        });
    }

    private void sendMessage() {
        String text = messageField.getText().trim();
        if (text.isEmpty()) return;
        if (typingSent) { out.println("TYPING:" + username + ":0"); typingSent = false; typingStopTimer.stop(); }

        String id = Long.toHexString(System.nanoTime()) + "-" + (int)(Math.random() * 100000);
        addMessageBubble(id, username, text, 0, true);
        out.println("MSG:" + id + ":" + username + ":" + text);
        messageField.setText("");
    }

    // ─── Server listener ─────────────────────────────────────────────────────

    private void startListening() {
        new Thread(() -> {
            try {
                String msg;
                while ((msg = in.readLine()) != null) {
                    final String line = msg;
                    SwingUtilities.invokeLater(() -> handleServerLine(line));
                }
            } catch (IOException ignored) {}
        }, "listener").start();
    }

    private void handleServerLine(String line) {
        if (line.startsWith("USER_COUNT:")) {
            String cnt = line.substring(11);
            userCountLabel.setText("● " + cnt + " Online");

        } else if (line.startsWith("NOTIF:")) {
            addNotification(line.substring(6));

        } else if (line.startsWith("TYPING:")) {
            String[] p = line.split(":", 3);
            if (p.length == 3) {
                String who = p[1]; boolean on = "1".equals(p[2]);
                if (!who.equals(username)) { if (on) typers.add(who); else typers.remove(who); updateTypingLabel(); }
            }

        } else if (line.startsWith("MSG:")) {
            // MSG:<ts>:<id>:<sender>:<text>
            String[] p = line.split(":", 5);
            if (p.length == 5) {
                long ts = parseLong(p[1]); String id = p[2], sender = p[3], text = p[4];
                typers.remove(sender); updateTypingLabel();
                if (sender.equals(username)) ackMessage(id, ts);
                else addMessageBubble(id, sender, text, ts, false);
            }

        } else if (line.startsWith("HISTORY_START:")) {
            awaitingHistory = true;
            addHistoryDivider();

        } else if (line.startsWith("HISTORY_MSG:") && awaitingHistory) {
            // HISTORY_MSG:<ts>:<id>:<sender>:<text>
            String[] p = line.split(":", 5);
            if (p.length == 5) {
                long ts = parseLong(p[1]); String id = p[2], sender = p[3], text = p[4];
                boolean mine = sender.equals(username);
                MessageData d = addMessageBubble(id, sender, text, ts, mine);
                if (mine && d.tickLabel != null) d.tickLabel.setText("✓✓");
            }

        } else if (line.equals("HISTORY_END")) {
            awaitingHistory = false;

        } else if (line.startsWith("EDITED:")) {
            // EDITED:<msgId>:<by>:<newText>
            String[] p = line.split(":", 4);
            if (p.length >= 4) applyEdit(p[1].trim(), p[3]);

        } else if (line.startsWith("DEL:")) {
            // DEL:<msgId>:<by>
            String[] p = line.split(":", 3);
            if (p.length >= 2) deleteMessageUI(p[1].trim());

        } else if (line.startsWith("REACT:")) {
            // REACT:<msgId>:<emoji>:<by>
            String[] p = line.split(":", 4);
            if (p.length == 4) addReaction(p[1].trim(), p[2].trim(), p[3].trim());

        } else if (line.startsWith("USERS:")) {
            String payload = line.substring(6);
            if (isAdmin()) showAdminUsersDialog(payload);
            // Refresh sidebar user list
            userListModel.clear();
            if (!payload.isEmpty()) {
                for (String entry : payload.split("\\|")) {
                    String[] f = entry.split(":", 3);
                    if (f.length >= 3)
                        userListModel.addElement(new UserEntry(f[0], "1".equals(f[1]), "1".equals(f[2])));
                    else if (f.length > 0)
                        userListModel.addElement(new UserEntry(f[0], false, false));
                }
            }

        } else if (line.startsWith("DM_FROM:")) {
            // DM_FROM:<from>:<text>
            String[] p = line.split(":", 3);
            if (p.length == 3) showDmNotification(p[1], p[2]);

        } else if (line.startsWith("DM_SENT:")) {
            String[] p = line.split(":", 3);
            if (p.length == 3) addNotification("📨 DM sent to " + p[1] + ": " + p[2]);

        } else if (line.startsWith("TOPIC:")) {
            roomTopic = line.substring(6);
            topicLabel.setText(roomTopic.isEmpty() ? "No topic set" : "📌 " + roomTopic);

        } else if (line.startsWith("MUTED:")) {
            JOptionPane.showMessageDialog(frame, "🔇 " + line.substring(6), "Muted", JOptionPane.WARNING_MESSAGE);

        } else if (line.startsWith("UNMUTED:")) {
            JOptionPane.showMessageDialog(frame, "🔊 " + line.substring(8), "Unmuted", JOptionPane.INFORMATION_MESSAGE);

        } else if (line.startsWith("KICKED:")) {
            JOptionPane.showMessageDialog(frame, "🚫 " + line.substring(7), "Removed", JOptionPane.ERROR_MESSAGE);
            System.exit(0);

        } else if (line.startsWith("ENDED:")) {
            JOptionPane.showMessageDialog(frame, "🏁 " + line.substring(6), "Room Ended", JOptionPane.INFORMATION_MESSAGE);
            System.exit(0);

        } else if (line.startsWith("ERROR:")) {
            JOptionPane.showMessageDialog(frame, line.substring(6), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showDmNotification(String from, String text) {
        JOptionPane.showMessageDialog(frame,
                "💬 " + from + " says:\n" + text,
                "Direct Message", JOptionPane.INFORMATION_MESSAGE);
    }

    private void updateTypingLabel() {
        if (typers.isEmpty()) { typingLabel.setText(" "); return; }
        String who = String.join(", ", typers);
        typingLabel.setText(who + (typers.size() == 1 ? " is typing…" : " are typing…"));
    }

    // ─── Networking / connect flow ────────────────────────────────────────────

    private void connectToServer() {
        // Username
        JTextField nameField = new JTextField(18);
        nameField.setFont(new Font("SansSerif", Font.PLAIN, 13));
        JPanel namePanel = new JPanel(new BorderLayout(6, 0));
        namePanel.add(new JLabel("Your name: "), BorderLayout.WEST);
        namePanel.add(nameField, BorderLayout.CENTER);

        int res = JOptionPane.showConfirmDialog(null, namePanel, "ChatSpace — Enter Name",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res != JOptionPane.OK_OPTION || nameField.getText().trim().isEmpty()) System.exit(0);
        username = nameField.getText().trim();

        try {
            socket = new Socket("localhost", 1234);
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));

            // Create or Join
            String[] opts = {"✨ Create Room", "🔗 Join Room"};
            int choice = JOptionPane.showOptionDialog(null, "What would you like to do?", "ChatSpace",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, opts, opts[0]);
            if (choice == JOptionPane.CLOSED_OPTION) System.exit(0);

            if (choice == 0) {
                // Build create dialog
                JTextField rnameField = new JTextField(18);
                JPasswordField pwField = new JPasswordField(18);
                JSpinner maxSpinner = new JSpinner(new SpinnerNumberModel(50, 2, 500, 1));

                JPanel cp = new JPanel(new GridLayout(3, 2, 8, 8));
                cp.add(new JLabel("Room Name:")); cp.add(rnameField);
                cp.add(new JLabel("Password (opt):")); cp.add(pwField);
                cp.add(new JLabel("Max Users:")); cp.add(maxSpinner);

                int r = JOptionPane.showConfirmDialog(null, cp, "Create Room",
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
                if (r != JOptionPane.OK_OPTION) System.exit(0);

                String rName = rnameField.getText().trim();
                if (rName.isEmpty()) rName = username + "'s Room";
                String pw = new String(pwField.getPassword()).trim();
                int maxU = (Integer) maxSpinner.getValue();

                out.println("CREATE:" + rName + ":" + username + ":" + pw + ":" + maxU);
                String resp = in.readLine();
                // CREATED:<code>:<name>:<admin>:<hasPw>:<maxUsers>
                String[] p = resp.split(":", 6);
                if (!resp.startsWith("CREATED") || p.length < 4) {
                    JOptionPane.showMessageDialog(null, "Error: " + resp); System.exit(0);
                }
                roomCode  = p[1]; roomName  = p[2]; adminName = p[3];

            } else {
                JTextField codeField = new JTextField(10);
                JPasswordField pwField = new JPasswordField(18);

                JPanel jp = new JPanel(new GridLayout(2, 2, 8, 8));
                jp.add(new JLabel("Room Code:")); jp.add(codeField);
                jp.add(new JLabel("Password:")); jp.add(pwField);

                int r = JOptionPane.showConfirmDialog(null, jp, "Join Room",
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
                if (r != JOptionPane.OK_OPTION) System.exit(0);

                String code = codeField.getText().trim().toUpperCase();
                String pw   = new String(pwField.getPassword()).trim();

                out.println("JOIN:" + code + ":" + username + ":" + pw);
                String resp = in.readLine();
                // JOINED:<code>:<name>:<admin>:<hasPw>:<maxUsers>:<topic>
                String[] p = resp.split(":", 7);
                if (resp.startsWith("ERROR")) {
                    JOptionPane.showMessageDialog(null, "❌ " + (p.length > 1 ? p[1] : "Unknown error"));
                    System.exit(0);
                }
                if (!resp.startsWith("JOINED") || p.length < 4) {
                    JOptionPane.showMessageDialog(null, "Bad response: " + resp); System.exit(0);
                }
                roomCode  = p[1]; roomName  = p[2]; adminName = p[3];
                if (p.length >= 7) roomTopic = p[6];
            }

        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Cannot connect to server:\n" + e.getMessage());
            System.exit(0);
        }
    }

    // ─── UI helpers ───────────────────────────────────────────────────────────

    private JLabel makeHeaderLabel(String text, Color fg) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("SansSerif", Font.BOLD, 12));
        l.setForeground(fg);
        return l;
    }

    private JButton makeHeaderButton(String text) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(255, 255, 255, 30));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        btn.setFont(new Font("SansSerif", Font.BOLD, 11));
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setOpaque(false);
        btn.setBorder(new EmptyBorder(6, 12, 6, 12));
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private JComponent makeAvatar(String name) {
        String letter = (name == null || name.isEmpty()) ? "?" : String.valueOf(Character.toUpperCase(name.charAt(0)));
        JLabel av = new JLabel(letter, SwingConstants.CENTER) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(colorFromName(name));
                g2.fillOval(0, 0, getWidth(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        av.setOpaque(false);
        av.setPreferredSize(new Dimension(34, 34));
        av.setFont(new Font("SansSerif", Font.BOLD, 14));
        av.setForeground(Color.WHITE);
        return av;
    }

    private Color colorFromName(String s) {
        if (s == null) return Color.GRAY;
        int h = s.hashCode();
        int[][] palette = {
                {99, 91, 255}, {16, 185, 129}, {245, 101, 101}, {251, 191, 36},
                {59, 130, 246}, {236, 72, 153}, {14, 165, 233}, {168, 85, 247}
        };
        int[] c = palette[Math.abs(h) % palette.length];
        return new Color(c[0], c[1], c[2]);
    }

    private void stylePopup(JPopupMenu menu) {
        menu.setBackground(Color.WHITE);
        menu.setBorder(new LineBorder(C_DIVIDER, 1, true));
    }

    private void scrollToBottom() {
        chatPanel.revalidate();
        chatPanel.repaint();
        SwingUtilities.invokeLater(() -> {
            JScrollBar v = chatScroll.getVerticalScrollBar();
            v.setValue(v.getMaximum());
        });
    }

    private long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0L; }
    }

    // ─── User cell renderer ───────────────────────────────────────────────────

    private class UserCellRenderer implements ListCellRenderer<UserEntry> {
        @Override
        public Component getListCellRendererComponent(JList<? extends UserEntry> list,
                                                      UserEntry value, int index, boolean selected, boolean focused) {

            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setBackground(selected ? new Color(0xede9fe) : C_SIDEBAR);
            row.setBorder(new EmptyBorder(6, 10, 6, 10));

            // Avatar
            JComponent av = makeAvatar(value.username);
            av.setPreferredSize(new Dimension(30, 30));
            row.add(av, BorderLayout.WEST);

            // Name + badges
            JPanel info = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            info.setOpaque(false);

            JLabel nameLbl = new JLabel(value.username);
            nameLbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
            nameLbl.setForeground(C_TEXT1);
            info.add(nameLbl);

            if (value.admin) {
                JLabel badge = new JLabel("admin");
                badge.setFont(new Font("SansSerif", Font.BOLD, 9));
                badge.setForeground(new Color(0x4338ca));
                badge.setBackground(new Color(0xede9fe));
                badge.setOpaque(true);
                badge.setBorder(new EmptyBorder(1, 4, 1, 4));
                info.add(badge);
            }
            if (value.muted) info.add(new JLabel("🔇"));

            // Online dot
            JLabel dot = new JLabel("●");
            dot.setFont(new Font("SansSerif", Font.PLAIN, 9));
            dot.setForeground(C_GREEN);

            row.add(info, BorderLayout.CENTER);
            row.add(dot,  BorderLayout.EAST);
            return row;
        }
    }

    // ─── WrapLayout (credits: Rob Camick) ────────────────────────────────────
    // Minimal FlowLayout that wraps children correctly inside BoxLayout panels

    private static class WrapLayout extends FlowLayout {
        WrapLayout(int align, int hgap, int vgap) { super(align, hgap, vgap); }
        @Override
        public Dimension preferredLayoutSize(Container target) {
            return layoutSize(target, true);
        }
        @Override
        public Dimension minimumLayoutSize(Container target) {
            return layoutSize(target, false);
        }
        private Dimension layoutSize(Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                int targetWidth = target.getSize().width == 0
                        ? Integer.MAX_VALUE : target.getSize().width;
                Insets insets = target.getInsets();
                int hgap = getHgap(), vgap = getVgap();
                int maxWidth = targetWidth - insets.left - insets.right;
                int x = 0, y = insets.top + vgap, rowH = 0;
                for (Component c : target.getComponents()) {
                    if (!c.isVisible()) continue;
                    Dimension d = preferred ? c.getPreferredSize() : c.getMinimumSize();
                    if (x + d.width > maxWidth && x > 0) {
                        y += rowH + vgap; rowH = 0; x = 0;
                    }
                    rowH = Math.max(rowH, d.height);
                    x += d.width + hgap;
                }
                return new Dimension(targetWidth, y + rowH + insets.bottom + vgap);
            }
        }
    }
}