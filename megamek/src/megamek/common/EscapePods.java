/*
* MegaMek -
* Copyright (C) 2000, 2001, 2002, 2003, 2004, 2005 Ben Mazur (bmazur@sev.org)
* Copyright (C) 2013 Edward Cullen (eddy@obsessedcomputers.co.uk)
* Copyright (C) 2020 The MegaMek Team
*
* This program is free software; you can redistribute it and/or modify it under
* the terms of the GNU General Public License as published by the Free Software
* Foundation; either version 2 of the License, or (at your option) any later
* version.
*
* This program is distributed in the hope that it will be useful, but WITHOUT
* ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
* FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
* details.
*/

package megamek.common;

/** This class describes a group of escape pods and/or lifeboats that has launched from a larger craft
 *
 * @author MKerensky
 */
public class EscapePods extends SmallCraft {
    private static final long serialVersionUID = 8128620143810186608L;
    
    protected int originalRideId;
    protected String originalRideExternalId;
    
    public static final String POD_EJECT_NAME = "Pods/Lifeboats from ";
    
    /**
     * Used to set up a group of launched pods/boats for large spacecraft per rules in SO p27
     * @param originalRide - the launching spacecraft
     * @param escapedThisRound - The number of pods that launched this round
     */
    public EscapePods(Aero originalRide, int escapedThisRound) {
        super();
        setCrew(new Crew(CrewType.CREW));
        setChassis(POD_EJECT_NAME);
        setModel(originalRide.getDisplayName());

        // Generate the display name, then add the original ride's name.
        StringBuffer newName = new StringBuffer(POD_EJECT_NAME);
        newName.append(originalRide.getDisplayName());
        displayName = newName.toString();
        
        //Pods and boats have an SI of 1 each
        setSI(escapedThisRound);
        
        //and an armor value of 4 each -- 1 point per location
        for (int i = 0; i < 4; i++) {
            setArmor(escapedThisRound, i);
        }
        
        setOriginalRideId(originalRide.getId());
        setOriginalRideExternalId(originalRide.getExternalIdAsString());
    }
    
    /**
     * This constructor is so MULParser can load these entities
     */
    public EscapePods() {
        super();
        setCrew(new Crew(CrewType.CREW));
        setChassis(POD_EJECT_NAME);
        //this constructor is just so that the MUL parser can read these units in so
        //assign some arbitrarily large number here for the internal so that locations will get 
        //the actual current number of pods correct.
        initializeSI(Integer.MAX_VALUE);
    }
    
    public EscapePods(Crew crew, IPlayer owner, IGame game) {
        super();
        setCrew(crew);
        setChassis(POD_EJECT_NAME);
        setModel(crew.getName());
        //setWeight(1);

        // Generate the display name, then add the original ride's name.
        StringBuffer newName = new StringBuffer(getDisplayName());
        displayName = newName.toString();

        // Finish initializing this unit.
        setOwner(owner);
        initializeInternal(crew.getSize(), Infantry.LOC_INFANTRY);
        if (crew.getSlotCount() > 1) {
            int dead = 0;
            for (int i = 0; i < crew.getSlotCount(); i++) {
                if (crew.isDead(i)) {
                    dead++;
                }
            }
            setInternal(crew.getSize() - dead, Infantry.LOC_INFANTRY);
        }
    }

    /**
     * @return the <code>int</code> id of this unit's original ride
     */
    public int getOriginalRideId() {
        return originalRideId;
    }

    /**
     * set the <code>int</code> id of this unit's original ride
     */
    public void setOriginalRideId(int originalRideId) {
        this.originalRideId = originalRideId;
    }

    /**
     * @return the <code>int</code> external id of this unit's original ride
     */
    public int getOriginalRideExternalId() {
        return Integer.parseInt(originalRideExternalId);
    }

    public String getOriginalRideExternalIdAsString() {
        return originalRideExternalId;
    }

    /**
     * set the <code>int</code> external id of this unit's original ride
     */
    public void setOriginalRideExternalId(String originalRideExternalId) {
        this.originalRideExternalId = originalRideExternalId;
    }

    public void setOriginalRideExternalId(int originalRideExternalId) {
        this.originalRideExternalId = Integer.toString(originalRideExternalId);
    }
    
    @Override
    public boolean isCrippled() {
        // Ejected crew should always attempt to flee according to Forced Withdrawal.
        return true;
    }
}
