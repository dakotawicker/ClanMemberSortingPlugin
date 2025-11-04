package net.runelite.client.plugins.clanmembersort;

import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.api.clan.ClanID;
import net.runelite.api.clan.ClanMember;
import net.runelite.api.clan.ClanRank;
import net.runelite.api.clan.ClanSettings;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.PluginPanel;

import javax.inject.Inject;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Clan Member Sort plugin
 *
 * Shows a side panel with your clan members and lets you sort them
 * by Name, Rank, or Join Date (ascending / descending).
 *
 * NOTE: This does NOT re-order the in-game clan list. It is a RuneLite-only view.
 */
@PluginDescriptor(
        name = "Clan Member Sort",
        description = "Adds a sortable clan member list panel",
        tags = {"clan", "members", "sort", "utility"}
)
public class ClanMemberSortPlugin extends Plugin
{
    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ClientToolbar clientToolbar;

    private NavigationButton navButton;
    private ClanMemberSortPanel panel;

    private int tickCounter = 0;

    @Override
    protected void startUp()
    {
        panel = new ClanMemberSortPanel();

        // Tiny placeholder icon so the navigation button builds without extra resources
        BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);

        navButton = NavigationButton.builder()
                .tooltip("Clan Member Sort")
                .icon(icon)
                .priority(5)
                .panel(panel)
                .build();

        clientToolbar.addNavigation(navButton);

        // Initial load (on client thread)
        updateClanMembers();
    }

    @Override
    protected void shutDown()
    {
        if (navButton != null)
        {
            clientToolbar.removeNavigation(navButton);
            navButton = null;
        }
        panel = null;
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        // Refresh every ~20 ticks (~12 seconds), just to keep things up to date
        tickCounter++;
        if (tickCounter % 20 == 0)
        {
            updateClanMembers();
        }
    }

    private void updateClanMembers()
    {
        if (panel == null)
        {
            return;
        }

        clientThread.invoke(() ->
        {
            ClanSettings settings = client.getClanSettings(ClanID.CLAN);
            if (settings == null)
            {
                panel.setClanMembers(new ArrayList<>());
                return;
            }

            List<ClanMember> members = settings.getMembers();
            panel.setClanMembers(members);
        });
    }

    // -------------------------
    // UI PANEL + TABLE MODEL
    // -------------------------

    private static class ClanMemberSortPanel extends PluginPanel
    {
        private final ClanMemberTableModel tableModel = new ClanMemberTableModel();
        private final JTable table = new JTable(tableModel);
        private final JComboBox<SortField> sortFieldBox;
        private final JCheckBox descendingCheck;

        private enum SortField
        {
            NAME("Name"),
            RANK("Rank"),
            JOIN_DATE("Join date");

            private final String display;

            SortField(String display)
            {
                this.display = display;
            }

            @Override
            public String toString()
            {
                return display;
            }
        }

        ClanMemberSortPanel()
        {
            setLayout(new BorderLayout());
            setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

            // --- Top controls (sort options) ---
            JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));

            sortFieldBox = new JComboBox<>(SortField.values());
            sortFieldBox.setSelectedItem(SortField.RANK); // default sort by rank

            descendingCheck = new JCheckBox("Descending");
            descendingCheck.setSelected(true);

            JButton refreshBtn = new JButton("Refresh");

            controls.add(new JLabel("Sort by: "));
            controls.add(sortFieldBox);
            controls.add(descendingCheck);
            controls.add(refreshBtn);

            add(controls, BorderLayout.NORTH);

            // --- Table setup ---
            table.setFillsViewportHeight(true);
            table.setAutoCreateRowSorter(false); // we do our own sorting

            JScrollPane scrollPane = new JScrollPane(table);
            add(scrollPane, BorderLayout.CENTER);

            // Listeners to apply sorting
            sortFieldBox.addActionListener(e -> applySort());
            descendingCheck.addActionListener(e -> applySort());
            refreshBtn.addActionListener(e -> applySort());
        }

        void setClanMembers(List<ClanMember> members)
        {
            List<ClanMemberInfo> rows = new ArrayList<>();
            for (ClanMember m : members)
            {
                if (m == null || m.getName() == null)
                {
                    continue;
                }
                rows.add(new ClanMemberInfo(
                        m.getName(),
                        m.getRank(),
                        m.getJoinDate()
                ));
            }

            tableModel.setRows(rows);
            applySort();
        }

        private void applySort()
        {
            SortField field = (SortField) sortFieldBox.getSelectedItem();
            boolean desc = descendingCheck.isSelected();
            tableModel.sort(field, desc);
        }
    }

    private static class ClanMemberInfo
    {
        private final String name;
        private final ClanRank rank;
        private final LocalDate joinDate;

        ClanMemberInfo(String name, ClanRank rank, LocalDate joinDate)
        {
            this.name = name;
            this.rank = rank;
            this.joinDate = joinDate;
        }

        public String getName()
        {
            return name;
        }

        public ClanRank getRank()
        {
            return rank;
        }

        public LocalDate getJoinDate()
        {
            return joinDate;
        }
    }

    private static class ClanMemberTableModel extends AbstractTableModel
    {
        private static final String[] COLS = {"Name", "Rank", "Join date"};

        private final DateTimeFormatter DATE_FMT =
                DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH);

        private List<ClanMemberInfo> rows = new ArrayList<>();

        void setRows(List<ClanMemberInfo> rows)
        {
            this.rows = rows;
            fireTableDataChanged();
        }

        void sort(ClanMemberSortPanel.SortField field, boolean descending)
        {
            if (rows == null || rows.isEmpty() || field == null)
            {
                return;
            }

            Comparator<ClanMemberInfo> cmp;

            switch (field)
            {
                case NAME:
                    cmp = Comparator.comparing(
                            r -> r.getName().toLowerCase(Locale.ENGLISH)
                    );
                    break;

                case RANK:
                    // Higher rank first by default
                    cmp = Comparator.comparingInt(
                            r -> r.getRank() != null ? r.getRank().ordinal() : Integer.MAX_VALUE
                    );
                    break;

                case JOIN_DATE:
                    // Older members first by default (earlier date = smaller)
                    cmp = Comparator.comparing(
                            r -> r.getJoinDate() != null ? r.getJoinDate() : LocalDate.MAX
                    );
                    break;

                default:
                    return;
            }

            if (descending)
            {
                cmp = cmp.reversed();
            }

            rows.sort(cmp);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount()
        {
            return rows.size();
        }

        @Override
        public int getColumnCount()
        {
            return COLS.length;
        }

        @Override
        public String getColumnName(int column)
        {
            return COLS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex)
        {
            ClanMemberInfo info = rows.get(rowIndex);
            switch (columnIndex)
            {
                case 0:
                    return info.getName();
                case 1:
                    return info.getRank() != null ? info.getRank().toString() : "";
                case 2:
                    return info.getJoinDate() != null
                            ? DATE_FMT.format(info.getJoinDate())
                            : "";
                default:
                    return "";
            }
        }

        @Override
        public Class<?> getColumnClass(int columnIndex)
        {
            switch (columnIndex)
            {
                case 0:
                    return String.class;
                case 1:
                    return String.class;
                case 2:
                    return String.class;
                default:
                    return Object.class;
            }
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex)
        {
            return false;
        }
    }
}

