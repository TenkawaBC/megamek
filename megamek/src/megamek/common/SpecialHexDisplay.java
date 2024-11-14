/*
 * MegaMek - Copyright (C) 2007 Ben Mazur (bmazur@sev.org)
 * Copyright © 2013 Edward Cullen (eddy@obsessedcomputers.co.uk)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details.
 */
package megamek.common;

import java.awt.Image;
import java.io.Serializable;
import java.util.Objects;

import megamek.client.ui.swing.GUIPreferences;
import megamek.common.enums.GamePhase;
import megamek.common.util.ImageUtil;
import megamek.common.util.fileUtils.MegaMekFile;

/**
 * @author dirk
 */
public class SpecialHexDisplay implements Serializable {
    private static final long serialVersionUID = 27470795993329492L;

    public enum Type {
        ARTILLERY_AUTOHIT(new MegaMekFile(Configuration.hexesDir(), "artyauto.gif").toString()) {
            @Override
            public boolean drawBefore() {
                return false;
            }

            @Override
            public boolean drawAfter() {
                return true;
            }
        },
        ARTILLERY_ADJUSTED(new MegaMekFile(Configuration.hexesDir(), "artyadj.gif").toString()) {
            @Override
            public boolean drawBefore() {
                return false;
            }

            @Override
            public boolean drawAfter() {
                return true;
            }
        },
        ARTILLERY_INCOMING(new MegaMekFile(Configuration.hexesDir(), "artyinc.gif").toString()),
        ARTILLERY_TARGET(new MegaMekFile(Configuration.hexesDir(), "artytarget.gif").toString()) {
            @Override
            public boolean drawBefore() {
                return false;
            }
        },
        ARTILLERY_HIT(new MegaMekFile(Configuration.hexesDir(), "artyhit.gif").toString()) {
            @Override
            public boolean drawBefore() {
                return false;
            }
        },
        ARTILLERY_DRIFT(new MegaMekFile(Configuration.hexesDir(), "artydrift.gif").toString()) {
            @Override
            public boolean drawBefore() {
                return false;
            }
        },
        ARTILLERY_MISS(new MegaMekFile(Configuration.hexesDir(), "artymiss.gif").toString()) {
            @Override
            public boolean drawBefore() {
                return false;
            }
        },
        BOMB_HIT(new MegaMekFile(Configuration.hexesDir(), "bombhit.gif").toString()) {
            @Override
            public boolean drawBefore() {
                return false;
            }
        },
        BOMB_DRIFT(new MegaMekFile(Configuration.hexesDir(), "bombdrift.gif").toString()) {
            @Override
            public boolean drawBefore() {
                return false;
            }
        },
        BOMB_MISS(new MegaMekFile(Configuration.hexesDir(), "bombmiss.gif").toString()) {
            @Override
            public boolean drawBefore() {
                return false;
            }
        },
        PLAYER_NOTE(new MegaMekFile(Configuration.hexesDir(), "note.png").toString()) {
            @Override
            public boolean drawAfter() {
                return true;
            }
        },
        ORBITAL_BOMBARDMENT(new MegaMekFile(Configuration.hexesDir(), "obinc.gif").toString());

        private transient Image defaultImage;
        private final String defaultImagePath;

        Type(String iconPath) {
            defaultImagePath = iconPath;
        }

        public void init() {
            if (defaultImagePath != null) {
                defaultImage = ImageUtil.loadImageFromFile(defaultImagePath);
            }

        }

        public Image getDefaultImage() {
            return defaultImage;
        }

        public void setDefaultImage(Image defaultImage) {
            this.defaultImage = defaultImage;
        }

        public boolean drawBefore() {
            return true;
        }

        public boolean drawAfter() {
            return false;
        }
    }

    /**
     * Defines that only the owner can see an obscured display.
     */
    public static int SHD_OBSCURED_OWNER = 0;
    /**
     * Defines that only the owner and members of his team can see an obscured
     * display.
     */
    public static int SHD_OBSCURED_TEAM = 1;
    /**
     * Defines that everyone can see an obscured display.
     */
    public static int SHD_OBSCURED_ALL = 2;

    private String info;
    private Type type;
    private int round;

    private Player owner;

    private int obscured = SHD_OBSCURED_ALL;

    public static int NO_ROUND = -99;

    public SpecialHexDisplay(Type type, int round, Player owner, String info) {
        this.type = type;
        this.info = info;
        this.round = round;
        this.owner = owner;
    }

    public SpecialHexDisplay(Type type, int round, Player owner, String info, int obscured) {
        this.type = type;
        this.info = info;
        this.round = round;
        this.owner = owner;
        this.obscured = obscured;
    }

    public boolean thisRound(int testRound) {
        if (NO_ROUND == round) {
            return true;
        }
        return testRound == round;
    }

    /** Does this SpecialHexDisplayObjet concern a round in the future? */
    public boolean futureRound(int testRound) {
        if (NO_ROUND == round) {
            return true;
        }
        return testRound > round;
    }

    /** Does this SpecialHexDisplayObjet concern a round in the past? */
    public boolean pastRound(int testRound) {
        if (NO_ROUND == round) {
            return true;
        }
        return testRound < round;
    }

    public String getInfo() {
        return info;
    }

    public void setInfo(String info) {
        this.info = info;
    }

    public int getRound() {
        return round;
    }

    public void setRound(int round) {
        this.round = round;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public Player getOwner() {
        return owner;
    }

    public void setOwner(Player owner) {
        this.owner = owner;
    }

    public void setObscuredLevel(int o) {
        if (o >= SHD_OBSCURED_OWNER && o <= SHD_OBSCURED_ALL) {
            obscured = o;
        }
    }

    public int getObscuredLevel() {
        return obscured;
    }

    /**
     * Determines whether this special hex should be obscured from the given
     * <code>Player</code>.
     *
     * @param other
     * @return
     */
    public boolean isObscured(Player other) {
        if (owner == null) {
            return false;
        }
        if ((obscured == SHD_OBSCURED_OWNER) && owner.equals(other)) {
            return false;
        } else if ((obscured == SHD_OBSCURED_TEAM) && (other != null)
                && (owner.getTeam() == other.getTeam())) {
            return false;
        }

        return obscured != SHD_OBSCURED_ALL;
    }

    public void setObscured(int obscured) {
        this.obscured = obscured;
    }

    /**
     * Determine whether the current SpecialHexDisplay should be displayed
     * Note Artillery Hits and Bomb Hits (direct hits on their targets) will always
     * display
     * in the appropriate phase. Other bomb- or artillery-related graphics are
     * optional.
     *
     * @param phase
     * @param curRound
     * @param playerChecking
     * @param guiPref
     * @return
     */
    public boolean drawNow(GamePhase phase, int curRound, Player playerChecking, GUIPreferences guiPref) {
        boolean shouldDisplay = thisRound(curRound)
                || (pastRound(curRound) && type.drawBefore())
                || (futureRound(curRound) && type.drawAfter());

        if (phase.isBefore(GamePhase.OFFBOARD)
                && ((type == Type.ARTILLERY_TARGET)
                        || type == Type.ARTILLERY_MISS
                        || (type == Type.ARTILLERY_HIT))) {
            shouldDisplay = shouldDisplay || thisRound(curRound - 1);
        }

        // Arty icons for the owner are drawn in BoardView1.drawArtillery
        // and shouldn't be drawn twice
        if (isOwner(playerChecking)
                && (type == Type.ARTILLERY_AUTOHIT
                        || type == Type.ARTILLERY_ADJUSTED
                        || type == Type.ARTILLERY_INCOMING
                        || type == Type.ARTILLERY_TARGET)) {
            return false;
        }

        // Only display obscured hexes to owner
        if (isObscured(playerChecking)) {
            return false;
        }

        // Hide icons the player doesn't want to see
        // Check user settings and Hide some "hits" because they are actually drifts
        // that did damage
        if (guiPref != null) {
            switch (type) {
                case ARTILLERY_HIT ->
                    shouldDisplay &= !this.info.contains(Messages.getString("ArtilleryMessage.drifted"));
                case ARTILLERY_MISS -> shouldDisplay &= guiPref.getBoolean(GUIPreferences.SHOW_ARTILLERY_MISSES);
                case ARTILLERY_DRIFT -> shouldDisplay &= guiPref.getBoolean(GUIPreferences.SHOW_ARTILLERY_DRIFTS);
                case BOMB_MISS -> shouldDisplay &= guiPref.getBoolean(GUIPreferences.SHOW_BOMB_MISSES);
                case BOMB_DRIFT -> shouldDisplay &= guiPref.getBoolean(GUIPreferences.SHOW_BOMB_DRIFTS);
            }
        }

        return shouldDisplay;
    }

    /**
     * @param toPlayer
     * @return
     */
    public boolean isOwner(Player toPlayer) {
        return (owner == null) || owner.equals(toPlayer);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if ((null == obj) || (getClass() != obj.getClass())) {
            return false;
        }
        final SpecialHexDisplay other = (SpecialHexDisplay) obj;
        return (type == other.type) && Objects.equals(owner, other.owner) && (round == other.round)
                && info.equals(other.info);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, owner, round);
    }

    @Override
    public String toString() {
        return "SHD: " + type.name() + ", " + "round " + round + ", by "
                + owner.getName();
    }
}
