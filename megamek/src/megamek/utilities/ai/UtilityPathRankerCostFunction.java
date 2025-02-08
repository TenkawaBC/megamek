package megamek.utilities.ai;

import megamek.client.bot.princess.CardinalEdge;
import megamek.common.*;
import megamek.common.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;


public record UtilityPathRankerCostFunction(CardinalEdge homeEdge, CostFunctionSwarmContext swarmContext, Board board) implements CostFunction {

    public UtilityPathRankerCostFunction {
        if (swarmContext == null) {
            swarmContext = new CostFunctionSwarmContext();
        }
        if (board == null) {
            throw new IllegalArgumentException("Board cannot be null");
        }
        initializeBoardTerrainLevels(board);
        swarmContext.initializeStrategicGoals(board, 5, 5);
    }

    @Override
    public double resolve(UnitAction unitAction, Map<Integer, UnitState> currentUnitStates, BehaviorParameters behaviorParameters) {
        if (swarmContext.getClusters().isEmpty()) {
            swarmContext.assignClusters(currentUnitStates.values());
        }
        BehaviorState state = determineBehaviorState(unitAction, currentUnitStates);

        double polynomialAggressionMod = polynomialAggressionMod(unitAction, currentUnitStates, behaviorParameters);
        double aggressionMod = calculateAggressionMod(unitAction, currentUnitStates, behaviorParameters);
        double fallMod = calculateFallMod(unitAction, currentUnitStates, behaviorParameters);
        double bravery = getBraveryMod(unitAction, currentUnitStates, behaviorParameters);
        double movementMod = calculateMovementMod(unitAction, currentUnitStates, behaviorParameters);
        double facingMod = calculateFacingMod(unitAction, currentUnitStates, behaviorParameters);
        double selfPreservationMod = calculateSelfPreservationMod(unitAction, currentUnitStates, behaviorParameters);
        double strategicMod = calculateStrategicGoalMod(unitAction, currentUnitStates, behaviorParameters);
        double formationMod = calculateFormationModifier(unitAction, currentUnitStates, behaviorParameters);
        double exposurePenalty = calculateExposurePenalty(unitAction, currentUnitStates, behaviorParameters);
        double healthMod = calculateHealthMod(unitAction, currentUnitStates, behaviorParameters);
        double nearbyUnitsMod = calculateNearbyUnitsMod(unitAction, currentUnitStates, behaviorParameters);
        double swarmMod = calculateSwarmCohesionMod(unitAction, currentUnitStates, behaviorParameters);
        double enemyPosMod = calculateEnemyPositioningMod(unitAction, currentUnitStates, behaviorParameters);
        double damageMod = calculateExpectedDamage(unitAction, currentUnitStates, behaviorParameters);
        double advancedCoverMod = calculateAdvancedCoverage(unitAction, currentUnitStates, behaviorParameters);
        double environmentMod = calculateImmediateEnvironmentMod(unitAction, currentUnitStates, behaviorParameters);
        double calculateMovementInertiaMod = calculateMovementInertia(unitAction, currentUnitStates, behaviorParameters);
        double anticrowding = calculateCrowdingTolerance(unitAction, currentUnitStates, behaviorParameters);

        List<Double> factors = List.of(
            polynomialAggressionMod,
            aggressionMod,
            fallMod,
            bravery,
            movementMod,
            facingMod,
            strategicMod,
            formationMod,
            exposurePenalty,
            healthMod,
            swarmMod,
            enemyPosMod,
            calculateMovementInertiaMod,
            damageMod,
            advancedCoverMod,
            nearbyUnitsMod,
            selfPreservationMod,
            environmentMod,
            anticrowding
        );

        double logSum = 0.0;
        int count = 0;
        for (double f : factors) {
            double safeFactor = Math.max(0.01, f);
            logSum += Math.log(safeFactor);
            count++;
        }
        double geometricMean = Math.exp(logSum / count);
        double geometricMeanAdjustedToBehavior = applyBehaviorState(state, geometricMean, unitAction, currentUnitStates, behaviorParameters);
        double utility = clamp01(geometricMeanAdjustedToBehavior);
        return utility;
    }

    static double clamp01(double value) {
        return Math.min(1.0, Math.max(0.0, value));
    }

    private BehaviorState determineBehaviorState(UnitAction action, Map<Integer, UnitState> states) {
        UnitState unit = states.get(action.id());
        double health = (unit.armorP() + unit.internalP()) / 2;
        int turnsStationary = unit.turnsWithoutMovement();

        if (unit.role() == UnitRole.SCOUT || turnsStationary > 1) {
            return BehaviorState.SCOUTING;
        } else if (health > 0.7 && getDamageAtRange(5, unit) > 10) {
            return BehaviorState.AGGRESSIVE;
        } else {
            return BehaviorState.DEFENSIVE;
        }
    }
    private double calculateSwarmCohesionMod(UnitAction action, Map<Integer, UnitState> states,
                                             BehaviorParameters params) {
        CostFunctionSwarmContext.SwarmCluster cluster = swarmContext.getClusterFor(states.get(action.id()));
        Coords centroid = cluster.getCentroid();

        double distanceToCentroid = action.finalPosition().distance(centroid);
        double vectorAlignment = calculateVectorAlignment(
            new Coords(action.fromX(), action.fromY()),
            action.finalPosition(),
            centroid
        );

        return clamp01(clamp01(params.p10() * (1 - distanceToCentroid/20) + params.p22() * vectorAlignment) + 0.1);
    }


    private double calculateVectorAlignment(Coords from, Coords to, Coords target) {
        Coords movementVector = to.subtract(from);
        Coords targetVector = target.subtract(from);
        return movementVector.cosineSimilarity(targetVector);
    }

    private double calculateEnemyPositioningMod(UnitAction action, Map<Integer, UnitState> states,
                                                BehaviorParameters params) {
        int playerId = states.get(action.id()).playerId();
        List<UnitState> validEnemies = nonAlliedUnits(action, states).stream()
            .filter(e -> e.playerId() != playerId)
            .filter(e -> !e.type().equals("MekWarrior") && !e.type().equals("EjectedCrew"))
            .filter(e -> !e.crippled())
            .toList();

        Coords enemyMedian = calculateEnemyMedian(validEnemies);
        double enemyMedianDistance = action.finalPosition().distance(enemyMedian);

        List<Double> top5EnemyDistances = validEnemies.stream()
            .map(e -> (double)e.position().distance(action.finalPosition()))
            .sorted()
            .limit(5)
            .toList();

        double proximityThreat = top5EnemyDistances.stream()
            .mapToDouble(d -> 1/(d+1))
            .sum();

        return clamp01(params.p11() * enemyMedianDistance/30 + params.p19() * proximityThreat);
    }

    private Coords calculateEnemyMedian(List<UnitState> enemyStates) {
        if (enemyStates.isEmpty()) {
            return new Coords(0, 0);
        }
        double sumX = 0;
        double sumY = 0;
        for (UnitState enemy : enemyStates) {
            Coords pos = enemy.position();
            sumX += pos.getX();
            sumY += pos.getY();
        }
        int avgX = (int) Math.round(sumX / enemyStates.size());
        int avgY = (int) Math.round(sumY / enemyStates.size());
        return new Coords(avgX, avgY);
    }

    private double calculateTerrainCover(Coords position) {
        double coverScore = 0;
        int index = position.getY() * board.getWidth() + position.getX();
        if (woodedTerrain.get(index)) {
            for (Coords adj : position.allAdjacent()) {
                if (insideBoard(adj)) {
                    int adjIndex = adj.getY() * board.getWidth() + adj.getX();
                    if (woodedTerrain.get(adjIndex)) {
                        coverScore += 0.2;
                    }
                }
            }
        } else {
            // Open terrain may be less desirable.
            coverScore -= 0.05;
        }
        // Return an average bonus (or simply the sum—adjust as desired)
        return coverScore;
    }

    private double calculateExpectedDamage(UnitAction action, Map<Integer, UnitState> states,
                                           BehaviorParameters params) {
        Coords finalPos = action.finalPosition();
        double damageSum = nonAlliedUnits(action, states).stream()
            .filter(e -> e.position().distance(finalPos) <= e.maxRange())
            .mapToDouble(e -> getDamageAtRange(finalPos.distance(e.position()), e))
            .sum();

        return clamp01(1 - params.p12() * damageSum/100);
    }

    private double calculateAdvancedCoverage(UnitAction action, Map<Integer, UnitState> states,
                                             BehaviorParameters params) {
        long coveringAllies = alliedUnits(action, states).stream()
            .filter(a -> a.position().distance(action.finalPosition()) <= a.maxRange() * 0.6)
            .filter(a -> hasLineOfSight(board, a.position(), action.finalPosition()))
            .count();

        double baseCoverage = 0.8 + (coveringAllies * params.p13());
        double densityBonus = calculateCoverDensity(action.finalPosition(), 3);

        return clamp01(baseCoverage + params.p21() * densityBonus);
    }

    private double calculateImmediateEnvironmentMod(UnitAction action,
                                                    Map<Integer, UnitState> states,
                                                    BehaviorParameters params) {
        Hex finalHex = board.getHex(action.finalPosition());
        if (finalHex == null) {
            return 1;
        }
        double coverScore = 0;
        int currentHeight = boardTerrainLevels[action.toY() * board.getWidth() + action.toX()];
        List<Coords> hexesAround = finalHex.getCoords().allAdjacent();
        for (var targetPosition : hexesAround) {
            Hex targetHex = board.getHex(targetPosition);
            if (targetHex == null) continue;

            coverScore += calculateHeightCover(action.finalPosition(), currentHeight);
            // Water depth analysis
            coverScore += getWaterCoverScore(params, targetHex);

            // Surrounding terrain analysis
            coverScore += calculateTerrainCover(action.finalPosition());

            // Building cover
            coverScore += calculateBuildingCovert(params, targetPosition);
        }
        return 1 + coverScore;
    }

    private static double getWaterCoverScore(BehaviorParameters params, Hex targetHex) {
        if (targetHex.hasDepth1WaterOrDeeper()) {
            return 0.3 * params.p15();
        }
        return 0.0;
    }

    private double calculateBuildingCovert(BehaviorParameters params, Coords targetPosition) {
        if (buildings.get(targetPosition.getY() * board.getWidth() + targetPosition.getX())) {
            return 0.4 * params.p15();
        }
        return 0.0;
    }

    static int[] boardTerrainLevels;
    static BitSet hasWaterLevel;
    static BitSet woodedTerrain;
    static BitSet buildings;
    static BitSet clearTerrain;
    static BitSet hazardousTerrain;

    private static final int[] HAZARDS = new int[]{
        Terrains.FIRE,
        Terrains.MAGMA,
        Terrains.ICE,
        Terrains.WATER,
        Terrains.BUILDING,
        Terrains.BRIDGE,
        Terrains.BLACK_ICE,
        Terrains.SNOW,
        Terrains.SWAMP,
        Terrains.MUD,
        Terrains.TUNDRA,
        Terrains.HAZARDOUS_LIQUID
    };

    private boolean insideBoard(Coords position) {
        int idx = position.getY() * board.getWidth() + position.getX();
        return idx < boardTerrainLevels.length && idx >= 0;
    }

    private void initializeBoardTerrainLevels(Board board) {
        boardTerrainLevels = new int[board.getWidth() * board.getHeight()];
        hazardousTerrain = new BitSet(board.getWidth() * board.getHeight());
        woodedTerrain = new BitSet(board.getWidth() * board.getHeight());
        buildings = new BitSet(board.getWidth() * board.getHeight());
        clearTerrain = new BitSet(board.getWidth() * board.getHeight());
        hasWaterLevel = new BitSet(board.getWidth() * board.getHeight());
        int idx;
        int level;
        int temp;
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                idx = y * board.getWidth() + x;
                Hex hex = board.getHex(x, y);
                if (hex != null) {
                    level = hex.floor();
                    temp = hex.terrainLevel(Terrains.BUILDING);
                    if (temp != Terrain.LEVEL_NONE) {
                        level += temp;
                        buildings.set(idx);
                    }
                    if (hex.containsTerrain(Terrains.WOODS)) {
                        woodedTerrain.set(idx);
                    }
                    if (hex.containsAnyTerrainOf(HAZARDS)) {
                        hazardousTerrain.set(idx);
                    }
                    if (hex.isClearHex()) {
                        clearTerrain.set(idx);
                    }
                    if (hex.hasDepth1WaterOrDeeper()) {
                        hasWaterLevel.set(idx);
                    }
                    boardTerrainLevels[idx] = level;
                }
            }
        }
    }

    private double calculateHeightCover(Coords position, int baseHeight) {
        return boardTerrainLevels[position.getY() * board.getWidth() + position.getX()] > baseHeight ? 0.2 : 0;
    }

    private double calculateCoverDensity(Coords position, int radius) {
        return (double) position.allAtDistanceOrLess(radius).stream()
            .filter(c -> containsCover(board, position, c))
            .count() / (6.0 * radius * (radius + 1)/2.0);
    }

    private double applyBehaviorState(BehaviorState state, double baseUtility,
                                      UnitAction action, Map<Integer, UnitState> states,
                                      BehaviorParameters params) {
        return switch (state) {
            case SCOUTING -> baseUtility *
                (1 + params.p17() * calculateScoutingBonus(action, states));
            case AGGRESSIVE -> baseUtility *
                (1 + params.p16() * calculateAggressiveBonus(action, states));
            case DEFENSIVE -> baseUtility *
                (1 + params.p18() * calculateDefensiveBonus(action, states));
        };
    }

    private double calculateMovementInertia(UnitAction action, Map<Integer, UnitState> currentUnitsState,
                                            BehaviorParameters params) {
        var state = currentUnitsState.get(action.id());
        int stagnantTurns = state.turnsWithoutMovement();
        double penalty = stagnantTurns > 2 ?
            params.p14() * (stagnantTurns - 2) * 0.2 :
            0;
        return clamp01(1 - penalty);
    }

    private double calculateDefensiveBonus(UnitAction action, Map<Integer, UnitState> states) {
        Hex targetHex = board.getHex(action.finalPosition());
        double bonus = 0;
        if (targetHex != null) {
            // Add bonus if there is a building at the target position.
            if (board.getBuildingAt(action.finalPosition()) != null) {
                bonus += 0.3;
            }
            // Check the surrounding hexes for an elevation advantage.
            int baseLevel = targetHex.getLevel();
            for (Coords c : action.finalPosition().allAtDistanceOrLess(2)) {
                Hex neighborHex = board.getHex(c);
                if (neighborHex != null && neighborHex.getLevel() < baseLevel) {
                    bonus += 0.05;
                }
            }
        }
        return bonus;
    }

    private double calculateFlankingPosition(int maxRange, Coords unitPos, List<Coords> strategicGoals) {
        if (strategicGoals.isEmpty()) {
            return 0;
        }
        double sumX = 0, sumY = 0;
        for (Coords goal : strategicGoals) {
            sumX += goal.getX();
            sumY += goal.getY();
        }
        int avgX = (int) Math.round(sumX / strategicGoals.size());
        int avgY = (int) Math.round(sumY / strategicGoals.size());
        Coords avgGoal = new Coords(avgX, avgY);
        double distance = unitPos.distance(avgGoal);
        return clamp01(distance / (maxRange + 1));
    }

    private double calculateScoutingBonus(UnitAction action, Map<Integer, UnitState> states) {
        UnitState unitState = states.get(action.id());
        double flankScore = calculateFlankingPosition(unitState.maxRange(), action.finalPosition(),
            swarmContext.getStrategicGoalsOnCoordsQuadrant(action.finalPosition()));
        double sensorCoverage = calculateSensorCoverage(action, states);
        return flankScore + sensorCoverage;
    }

    private double calculateSensorCoverage(UnitAction action, Map<Integer, UnitState> states) {
        return 1.0;
    }

    private boolean hasLineOfSight(Board board, Coords from, Coords to) {
        List<Coords> line = getHexLine(from, to);
        // Skip the first and last hex if desired (assuming the source and target hexes are allowed)
        for (int i = 1; i < line.size() - 1; i++) {
            Hex hex = board.getHex(line.get(i));
            // If the hex is missing or not clear, then the LOS is blocked.
            if (hex == null || !hex.isClearHex()) {
                return false;
            }
        }
        return true;
    }
    private List<Coords> getHexLine(Coords a, Coords b) {
        CubeCoords ac = a.toCube();
        CubeCoords bc = b.toCube();
        int N = a.distance(b);
        List<Coords> results = new ArrayList<>();
        for (int i = 0; i <= N; i++) {
            double t = (N == 0) ? 0.0 : (double) i / N;
            CubeCoords lerped = CubeCoords.lerp(ac, bc, t);
            CubeCoords rounded = lerped.roundToNearestHex();
            results.add(rounded.toOffset());
        }
        return results;
    }

    private boolean containsCover(Board board, Coords position, Coords hexLocation) {
        Hex candidate = board.getHex(hexLocation);
        if (candidate == null) {
            return false;
        }
        // If the candidate hex is not clear, we assume it offers cover.
        if (!candidate.isClearHex()) {
            return true;
        }
        // Alternatively, if the candidate hex is at a higher elevation than the hex at 'position',
        // it may also be considered cover.
        Hex currentHex = board.getHex(position);
        if (currentHex != null && candidate.getLevel() > currentHex.getLevel()) {
            return true;
        }
        return false;
    }


    private double calculateAggressiveBonus(UnitAction action, Map<Integer, UnitState> states) {
        double damagePotential = getDamageAtRange(
            distanceToClosestNonAlliedUnit(action, states),
            states.get(action.id())
        );
        double movementBonus = action.hexesMoved() > 5 ? 0.3 : 0;
        return damagePotential/100 + movementBonus;
    }

    private static int getDamageAtRange(int distance, UnitState unitState) {
        var entity = unitState.entity();
        if (entity == null) {
            return 0;
        }
        return entity.getWeaponList().stream()
            .filter(w -> w.getType().longRange >= distance)
            .mapToInt(w -> w.getType().getDamage(distance))
            .sum();
    }

    private static List<UnitState> alliedUnits(UnitAction unitAction, Map<Integer, UnitState> currentUnitStates) {
        var playerId = currentUnitStates.get(unitAction.id()).playerId();
        return currentUnitStates.values().stream().filter(
            u -> u.playerId() == playerId
        ).toList();
    }

    private static List<UnitState> nonAlliedUnits(UnitAction unitAction, Map<Integer, UnitState> currentUnitStates) {
        var playerId = currentUnitStates.get(unitAction.id()).playerId();
        return currentUnitStates.values().stream().filter(
            u -> u.playerId() != playerId
        ).toList();
    }

    private static int distanceToClosestNonAlliedUnit(UnitAction unitAction, Map<Integer, UnitState> currentUnitStates) {
        var position = unitAction.finalPosition();
        return nonAlliedUnits(unitAction, currentUnitStates).stream()
            .map(UnitState::position)
            .mapToInt(c -> c.distance(position))
            .min().orElse(0);
    }

    private static Coords closestNonAlliedUnit(UnitAction unitAction, Map<Integer, UnitState> currentUnitStates) {
        var position = unitAction.finalPosition();
        return nonAlliedUnits(unitAction, currentUnitStates).stream()
            .map(UnitState::position)
            .min(Comparator.comparingInt(c -> c.distance(position)))
            .orElse(null);
    }

    private static UnitState closestNonAlliedEntity(UnitAction unitAction, Map<Integer, UnitState> currentUnitStates) {
        var position = unitAction.finalPosition();
        return nonAlliedUnits(unitAction, currentUnitStates).stream()
            .min(Comparator.comparingInt(u -> u.position().distance(position)))
            .orElse(null);
    }

    private double calculateHerdingMod(UnitAction unitAction, Map<Integer, UnitState> currentUnitStates, BehaviorParameters behaviorParameters) {
        if (alliedUnits(unitAction, currentUnitStates).size() == 1) {
            return 0;
        }

        double finalDistance = distanceToClosestNonAlliedUnit(unitAction, currentUnitStates);
        double herding = behaviorParameters.p8();
        return finalDistance * herding;
    }

    private double polynomialAggressionMod(UnitAction unitAction, Map<Integer, UnitState> currentUnitStates, BehaviorParameters behaviorParameters) {
        double alternativeActionsUtility = behaviorParameters.p1() * behaviorParameters.p1();
        return clamp01(1 - behaviorParameters.p1() + alternativeActionsUtility);
    }

    // P1
    private double calculateAggressionMod(UnitAction unitAction, Map<Integer, UnitState> currentUnitStates, BehaviorParameters behaviorParameters) {
        double distToEnemy = distanceToClosestNonAlliedUnit(unitAction, currentUnitStates);
        var self = currentUnitStates.get(unitAction.id()).type();
        boolean isInfantry = Objects.equals(self, "Infantry") || Objects.equals(self, "BattleAmor") || Objects.equals(self, "Mekwarrior")
            || Objects.equals(self, "EjectedCrew");

        if (distToEnemy == 0 && isInfantry) {
            distToEnemy = 2;
        }

        int maxRange = Math.max(1, currentUnitStates.get(unitAction.id()).maxRange());

        double weight = behaviorParameters.p1();
        double aggression = clamp01(maxRange / distToEnemy);
        return clamp01(1.1 - weight + aggression * weight);
    }

    // P5
    private static double calculateFacingMod(UnitAction unitAction, Map<Integer, UnitState> currentUnitStates, BehaviorParameters behaviorParameters) {
        int facingDiff = getFacingDiff(unitAction, currentUnitStates);
        return 1 - behaviorParameters.p5() + ((facingDiff * behaviorParameters.p5()) / (1 + behaviorParameters.p5()));
    }

    private static int getFacingDiff(UnitAction unitAction, Map<Integer, UnitState> currentUnitStates) {
        Coords closest = closestNonAlliedUnit(unitAction, currentUnitStates);
        int desiredFacing = (closest.direction(unitAction.finalPosition()) + 3) % 6;
        int currentFacing = unitAction.facing();
        int facingDiff;
        if (currentFacing == desiredFacing) {
            facingDiff = 0;
        } else if ((currentFacing == ((desiredFacing + 1) % 6))
            || (currentFacing == ((desiredFacing + 5) % 6))) {
            facingDiff = 0;
        } else if ((currentFacing == ((desiredFacing + 2) % 6))
            || (currentFacing == ((desiredFacing + 4) % 6))) {
            facingDiff = 1;
        } else {
            facingDiff = 2;
        }
        return facingDiff;
    }

    private static boolean meksAndTanks(UnitState unitState) {
        return (Objects.equals(unitState.type(), "BipedMek") || Objects.equals(unitState.type(), "QuadMek")
            || Objects.equals(unitState.type(), "Tank") || Objects.equals(unitState.type(), "TripodMek"));
    }

    private static double calculateCrowdingTolerance(UnitAction unitAction, Map<Integer, UnitState> currentUnitStates, BehaviorParameters behaviorParameters) {
        var self = currentUnitStates.get(unitAction.id());
        if (!(Objects.equals(self.type(), "BipedMek") || Objects.equals(self.type(), "QuadMek")
            || Objects.equals(self.type(), "Tank") || Objects.equals(self.type(), "TripodMek"))) {
            return 0.0;
        }
        var antiCrowding = behaviorParameters.p5();
        if (antiCrowding == 0) {
            return 0;
        }

        var antiCrowdingFactor = (10.0 / (11 - antiCrowding));
        final double herdingDistance = 2;
        final double closingDistance = Math.ceil(Math.max(3.0, 12 * 0.6));
        var position = unitAction.finalPosition();
        var crowdingFriends = alliedUnits(unitAction, currentUnitStates).stream()
            .filter(UtilityPathRankerCostFunction::meksAndTanks)
            .map(UnitState::position)
            .filter(c -> c.distance(position) <= herdingDistance)
            .count();

        var crowdingEnemies = nonAlliedUnits(unitAction, currentUnitStates).stream()
            .map(UnitState::position)
            .filter(c -> c.distance(position) <= closingDistance)
            .count();
        double friendsCrowdingTolerance = antiCrowdingFactor * crowdingFriends;
        double enemiesCrowdingTolerance = antiCrowdingFactor * crowdingEnemies;
        return friendsCrowdingTolerance + enemiesCrowdingTolerance;
    }

    // P4
    private static double calculateMovementMod(UnitAction unitAction, Map<Integer, UnitState> currentUnitStates, BehaviorParameters behaviorParameters) {
        var tmmFactor = behaviorParameters.p4();
        var tmm = Compute.getTargetMovementModifier(unitAction.hexesMoved(), unitAction.jumping(), false, null);
        var tmmValue = clamp01(tmm.getValue() / 8.0);
        return tmmValue * tmmFactor;
    }

    // P3
    private static double getBraveryMod(UnitAction unitAction, Map<Integer, UnitState> currentUnitStates, BehaviorParameters behaviorParameters) {
        var closestEnemy = closestNonAlliedEntity(unitAction, currentUnitStates);
        var distanceToClosestEnemy = distanceToClosestNonAlliedUnit(unitAction, currentUnitStates);
        int damageTaken = getDamageAtRange(distanceToClosestEnemy, closestEnemy);
        int damageCaused = getDamageAtRange(distanceToClosestEnemy, currentUnitStates.get(unitAction.id()));
        double successProbability = 1d - unitAction.chanceOfFailure();
        return clamp01(0.1 + clamp01((successProbability * damageCaused * behaviorParameters.p3()) - damageTaken));
    }

    // P2
    private static double calculateFallMod(UnitAction unitAction, Map<Integer, UnitState> currentUnitStates, BehaviorParameters behaviorParameters) {
        double pilotingFailure = unitAction.chanceOfFailure();
        double fallShameFactor = behaviorParameters.p2();
        return clamp01((1 - fallShameFactor) + (1 - pilotingFailure) * fallShameFactor);
    }

    private static double calculateNearbyUnitsMod(UnitAction unitAction, Map<Integer, UnitState> currentUnitStates, BehaviorParameters behaviorParameters) {
        double weight = behaviorParameters.p6();
        double distance = distanceToClosestNonAlliedUnit(unitAction, currentUnitStates);
        return clamp01(1.1 - weight + (1.0 / (distance + 1)) * weight);
    }

    private static double calculateHealthMod(UnitAction unitAction, Map<Integer, UnitState> currentUnitStates, BehaviorParameters behaviorParameters) {
        double weight = behaviorParameters.p6();
        double health = (unitAction.armorP() + unitAction.internalP()) / 2;
        if (health < 0.7) {
            weight *= 1.5; // More cautious when damaged
        }
        return health * weight;
    }

    // P6
    private double calculateSelfPreservationMod(UnitAction unitAction, Map<Integer, UnitState> currentUnitStates, BehaviorParameters behaviorParameters) {
        double weight = behaviorParameters.p6();
        double health = (unitAction.armorP() + unitAction.internalP()) / 2;
        if (health < 0.7) {
            weight *= 1.5; // More cautious when damaged
        }
        if (currentUnitStates.get(unitAction.id()).crippled()) {
            int newDistanceToHome = distanceToHomeEdge(unitAction.finalPosition());
            int currentDistanceToHome = distanceToHomeEdge(new Coords(unitAction.fromX(), unitAction.fromY()));

            double deltaDistance = currentDistanceToHome - newDistanceToHome;
            double selfPreservationMod;

            // normally, we favor being closer to the edge we're trying to get to
            if (deltaDistance > 0 && currentDistanceToHome > 0) {
                selfPreservationMod = 1.0 - newDistanceToHome / (double) currentDistanceToHome;
            } else if (deltaDistance < 0){
                selfPreservationMod = 1.0 - currentDistanceToHome / (double) newDistanceToHome;
            } else {
                selfPreservationMod = 1.0;
            }

            return clamp01(1.1 - weight + selfPreservationMod * weight);
        }
        return clamp01(health * weight);
    }

    private int distanceToHomeEdge(Coords position) {
        return switch (homeEdge) {
            case SOUTH -> board.getHeight() - position.getY() - 1;
            case WEST -> position.getX();
            case EAST -> board.getWidth() - position.getX() - 1;
            default -> position.getY();
        };
    }

    private double calculateStrategicGoalMod(UnitAction unitAction, Map<Integer, UnitState> currentUnitStates, BehaviorParameters behaviorParameters) {
        // Existing strategic goal calculation
        double maxGoalUtility = 0.0;
        for (Coords goal : swarmContext.getStrategicGoalsOnCoordsQuadrant(unitAction.finalPosition())) {
            double distance = unitAction.finalPosition().distance(goal);
            double utility = (10.0 / (distance + 1.0));
            maxGoalUtility = Math.max(maxGoalUtility, utility);
        }
        if (maxGoalUtility == 0.0) {
            return 1.0;
        }
        return clamp01(maxGoalUtility * behaviorParameters.p7());
    }

    private double calculateFormationModifier(UnitAction unitAction, Map<Integer, UnitState> currentUnitStates, BehaviorParameters behaviorParameters) {
        if (currentUnitStates.get(unitAction.id()) != null) {
            CostFunctionSwarmContext.SwarmCluster cluster = swarmContext.getClusterFor(currentUnitStates.get(unitAction.id()));

            double lineMod = calculateLineFormationMod(cluster, unitAction, currentUnitStates, behaviorParameters);
            double spacingMod = calculateOptimalSpacingMod(cluster, unitAction, currentUnitStates, behaviorParameters);
            double coverageMod = calculateCoverageModifier(unitAction, currentUnitStates, behaviorParameters);

            return clamp01(lineMod * coverageMod * spacingMod  * behaviorParameters.p8());
        } else {
            return 1.0;
        }
    }

    private double calculateCoverageModifier(UnitAction unitAction, Map<Integer, UnitState> currentUnitStates, BehaviorParameters behaviorParameters) {
        long coveringAllies = alliedUnits(unitAction, currentUnitStates).stream()
            .filter(a -> a.position().distance(unitAction.finalPosition()) <=
                a.maxRange() * 0.6)
            .count();

        return 0.8 + (coveringAllies * behaviorParameters.p4());
    }

    private double calculateOptimalSpacingMod(CostFunctionSwarmContext.SwarmCluster cluster, UnitAction unitAction, Map<Integer, UnitState> currentUnitStates, BehaviorParameters behaviorParameters) {
        double avgDistance = cluster.getMembers().stream()
            .filter(m -> m.id() != unitAction.id())
            .mapToDouble(m -> m.position().distance(unitAction.finalPosition()))
            .average()
            .orElse(0);

        // Ideal spacing between 3-5 hexes
        if (avgDistance < 3) return 0.8 * behaviorParameters.p5();
        if (avgDistance > 5) return 0.9 * behaviorParameters.p5();
        return behaviorParameters.p5();
    }


    private double calculateLineFormationMod(CostFunctionSwarmContext.SwarmCluster cluster, UnitAction unitAction, Map<Integer, UnitState> currentUnitStates, BehaviorParameters behaviorParameters) {
        UnitState unit = currentUnitStates.get(unitAction.id());
        if (unit.entity() == null) {
            return 1.0;
        }

        Coords currentPos = new Coords(unitAction.fromX(), unitAction.fromY());
        Coords newPos = unitAction.finalPosition();
        double optimalRange = currentUnitStates.get(unitAction.id()) != null ? currentUnitStates.get(unitAction.id()).maxRange() * 0.6 : 0.0;

        Coords primaryThreat = calculatePrimaryThreatPosition(unitAction, currentUnitStates);
        int threatDirection = cluster.getCentroid().direction(primaryThreat);

        Coords idealPosition = calculateLinePosition(
            cluster.getCentroid(),
            threatDirection,
            cluster.getMembers().indexOf(currentUnitStates.get(unitAction.id())),
            optimalRange
        );

        double formationQuality = unit.role().equals(UnitRole.AMBUSHER) ||
            unit.role().equals(UnitRole.STRIKER) ||
            unit.role().equals(UnitRole.SCOUT)
            ? calculateOrbitModifier(unitAction, currentUnitStates, primaryThreat)
            : calculateFormationQuality(newPos, idealPosition);

        double rangeQuality = calculateRangeQuality(newPos, primaryThreat, optimalRange, unit.maxRange());
        double forwardBias = calculateForwardBias(currentPos, newPos, primaryThreat);
        double borderPenalty = calculateBorderPenalty(newPos);

        double positionQuality = ((rangeQuality * 0.5) +
            (formationQuality * 0.3) +
            (forwardBias * 0.2) -
            borderPenalty);

        return clamp01(positionQuality);
    }

    private Coords calculateLinePosition(Coords centroid, int threatDirection, int unitIndex, double optimalRange) {
        Coords basePosition = centroid.translated(threatDirection, (int) Math.round(optimalRange));

        int lateralDirection = (unitIndex % 2 == 0) ?
            (threatDirection + 2) % 6 :
            (threatDirection + 4) % 6;

        int lateralDistance = unitIndex + 2;
        return basePosition.translated(lateralDirection, lateralDistance);
    }

    private double calculateRangeQuality(Coords newPos, Coords threatPos, double optimalRange, double maxRange) {
        double distance = newPos.distance(threatPos);

        // Quadratic penalty outside optimal range
        if (distance > optimalRange) {
            double overRange = distance - optimalRange;
            return Math.max(0, 1 - Math.pow(overRange / (maxRange - optimalRange), 2));
        }
        // Bonus for being in optimal range
        return 1 + (1 - (distance / optimalRange));
    }

    private double calculateFormationQuality(Coords newPos, Coords idealPos) {
        double distance = newPos.distance(idealPos);
        return 1.0 / (1.0 + distance * 0.5); // Gentle falloff
    }

    private double calculateForwardBias(Coords currentPos, Coords newPos, Coords threatPos) {
        double currentDistance = currentPos.distance(threatPos);
        double newDistance = newPos.distance(threatPos);

        // Reward moving closer to threat, penalize retreating
        return clamp01(1.5 - (newDistance / currentDistance));
    }

    private double calculateBorderPenalty(Coords position) {
        int boardWidth = board.getWidth();
        int boardHeight = board.getHeight();
        int borderDistance = Math.min(
            position.getX(),
            Math.min(
                position.getY(),
                Math.min(
                    boardWidth - position.getX(),
                    boardHeight - position.getY()
                )
            )
        );
        return borderDistance < 5 ? (5 - borderDistance) * 0.2 : 0;
    }

    private Coords calculatePrimaryThreatPosition(UnitAction unitAction, Map<Integer, UnitState> currentUnitStates) {
        var position = unitAction.finalPosition();
        var coords = nonAlliedUnits(unitAction, currentUnitStates).stream()
            .map(UnitState::position)
            .sorted(Comparator.comparingInt(c -> c.distance(position)))
            .limit(3)
            .toList();

        return Coords.average(coords);
    }

    private static final double ORBIT_RANGE_VARIANCE = 0.2; // 20% range flexibility

    private double calculateOrbitModifier(UnitAction unitAction, Map<Integer, UnitState> currentUnitStates, Coords threatPos) {

        Coords currentPos = unitAction.currentPosition();
        Coords newPos = unitAction.finalPosition();


        double optimalRange = currentUnitStates.get(unitAction.id()).maxRange() * 0.6;
        int orbitDirection = calculateOrbitDirection(unitAction, threatPos);

        Coords orbitPos = calculateOrbitPosition(currentPos, threatPos, optimalRange, orbitDirection);
        double currentDistanceFromPosition = Math.max(1d, orbitPos.distance(newPos));
        double distanceQuality = 1.0 - Math.abs(newPos.distance(threatPos) - optimalRange)/(optimalRange * ORBIT_RANGE_VARIANCE) / currentDistanceFromPosition;
        double directionQuality = calculateOrbitDirectionQuality(currentPos, newPos, orbitDirection);
        double movementBonus = unitAction.hexesMoved() > 0 ? 1.1 : 0.9;

        return clamp01((distanceQuality * 0.6) + (directionQuality * 0.4)) * movementBonus;
    }

    private int calculateOrbitDirection(UnitAction unitAction, Coords threatPos) {
        // Alternate direction based on unit ID to create swirling pattern
        return (unitAction.id() % 2 == 0) ?
            (threatPos.direction(unitAction.finalPosition()) + 1) % 6 : // Clockwise
            (threatPos.direction(unitAction.finalPosition()) + 5) % 6;  // Counter-clockwise
    }

    private Coords calculateOrbitPosition(Coords currentPos, Coords threatPos, double targetRange, int orbitDirection) {
        // 1. Calculate current orbital angle
        double currentDistance = currentPos.distance(threatPos);
        Coords idealPos = threatPos.translated(orbitDirection, (int) Math.round(targetRange));

        // 2. Maintain minimum range if too close
        if(currentDistance < targetRange * 0.8) {
            return idealPos.translated(orbitDirection, 1);
        }

        // 3. Adjust position while maintaining range
        return idealPos.translated(
            (orbitDirection + 3) % 6, // Opposite direction
            (int) Math.round((currentDistance - targetRange)/2)
        );
    }

    private double calculateOrbitDirectionQuality(Coords oldPos, Coords newPos, int desiredDirection) {
        int actualDirection = oldPos.direction(newPos);
        int directionDiff = Math.min(
            Math.abs(desiredDirection - actualDirection),
            6 - Math.abs(desiredDirection - actualDirection)
        );

        // Quadratic penalty for wrong direction
        return 1.0 - (directionDiff * directionDiff * 0.05);
    }

    private double calculateExposurePenalty(UnitAction unitAction, Map<Integer, UnitState> currentUnitStates, BehaviorParameters behaviorParameters) {
        UnitState unit = currentUnitStates.get(unitAction.id());
        if (unit.role() == UnitRole.AMBUSHER || unit.role() == UnitRole.SCOUT || unit.role() == UnitRole.MISSILE_BOAT || unit.role() == UnitRole.SNIPER) { // SCOUT/FLANKER
            long threateningEnemies = nonAlliedUnits(unitAction, currentUnitStates).stream()
                .filter(e -> e.maxRange() <= unitAction.finalPosition().distance(e.position()))
                .count();

            if (validateUnitCoverage(unitAction, currentUnitStates, behaviorParameters)) {
                double exposureScore = 1 + (threateningEnemies * 0.3);
                return 1.0 / exposureScore  * behaviorParameters.p9();
            } else {
                double exposureScore = 1 + (threateningEnemies * 0.5);
                return 1.0 / exposureScore * behaviorParameters.p9();
            }
        }
        return behaviorParameters.p9();
    }

    public boolean validateUnitCoverage(UnitAction unitAction, Map<Integer, UnitState> currentUnitStates, BehaviorParameters behaviorParameters) {
        // Check 0.6x coverage from at least one ally
        return alliedUnits(unitAction, currentUnitStates).stream()
            .filter(e -> e.id() != unitAction.id())
            .filter(ally -> ally.position().distance(unitAction.finalPosition()) <= ally.maxRange() * 0.6)
            .count() >= 2;
    }

    public static class CostFunctionSwarmContext {

        private final Map<Integer, Integer> enemyTargetCounts = new HashMap<>();
        private final List<Coords> strategicGoals = new Vector<>();
        private Coords currentCenter;

        private int quadrantHeight = 0;
        private int quadrantWidth = 0;
        private int offsetX = 0;
        private int offsetY = 0;

        private int clusterUnitsSize = 0;

        private final Map<Integer, SwarmCluster> unitClusters = new HashMap<>();
        private final List<SwarmCluster> clusters = new ArrayList<>();

        public CostFunctionSwarmContext() {;
        }

        /**
         * Record an enemy target, incrementing the number of units targeting the enemy this turn
         * @param enemyId The enemy id
         */
        @SuppressWarnings("unused")
        public void recordEnemyTarget(int enemyId) {
            enemyTargetCounts.put(enemyId, enemyTargetCounts.getOrDefault(enemyId, 0) + 1);
        }

        /**
         * Get the number of times an enemy has been targeted
         * @param enemyId The enemy id
         * @return The number of times the enemy has been targeted
         */
        @SuppressWarnings("unused")
        public int getEnemyTargetCount(int enemyId) {
            return enemyTargetCounts.getOrDefault(enemyId, 0);
        }

        /**
         * Reset the enemy target counts
         */
        public void resetEnemyTargets() {
            enemyTargetCounts.clear();
        }

        /**
         * Add a strategic goal to the list of goals, a strategic goal is simply a coordinate which we want to move towards,
         * its mainly used for double blind games where we don't know the enemy positions, the strategic goals help
         * distribute the map evenly accross the units inside the swarm to cover more ground and find the enemy faster
         * @param coords  The coordinates to add
         */
        public void addStrategicGoal(Coords coords) {
            strategicGoals.add(coords);
        }

        /**
         * Remove a strategic goal from the list of goals
         * @param coords The coordinates to remove
         */
        @SuppressWarnings("unused")
        public void removeStrategicGoal(Coords coords) {
            strategicGoals.remove(coords);
        }

        /**
         * Remove strategic goals in a radius around the given coordinates
         * @param coords The center coordinates
         * @param radius The radius to remove goals
         */
        @SuppressWarnings("unused")
        public void removeStrategicGoal(Coords coords, int radius) {
            for (var c : coords.allAtDistanceOrLess(radius)) {
                strategicGoals.remove(c);
            }
        }

        /**
         * Get the strategic goals on the quadrant of the given coordinates
         * @param coords The coordinates to check
         * @return A list of strategic goals on the quadrant
         */
        public List<Coords> getStrategicGoalsOnCoordsQuadrant(Coords coords) {
            QuadrantParameters quadrant = getQuadrantParameters(coords);
            Coords coord;
            List<Coords> goals = new Vector<>();
            for (int i = quadrant.startX(); i < quadrant.endX(); i++) {
                for (int j = quadrant.startY(); j < quadrant.endY(); j++) {
                    coord = new Coords(i, j);
                    if (strategicGoals.contains(coord)) {
                        goals.add(coord);
                    }
                }
            }
            return goals;
        }

        private QuadrantParameters getQuadrantParameters(Coords coords) {
            int x = coords.getX();
            int y = coords.getY();
            int startX = offsetX + (x / quadrantWidth) * quadrantWidth;
            int startY = offsetY + (y / quadrantHeight) * quadrantHeight;
            int endX = startX + quadrantWidth;
            int endY = startY + quadrantHeight;
            return new QuadrantParameters(startX, startY, endX, endY);
        }

        /**
         * Set the current center of the swarm
         * @param adjustedCenter The new center
         */
        public void setCurrentCenter(Coords adjustedCenter) {
            this.currentCenter = adjustedCenter;
        }

        /**
         * Get the current center of the swarm
         * @return The current center
         */
        public @Nullable Coords getCurrentCenter() {
            return currentCenter;
        }

        public boolean isUnitTooFarFromCluster(UnitAction unit, Map<Integer, UnitState> currentUnitState, double v) {
            SwarmCluster cluster = getClusterFor(currentUnitState.get(unit.id()));
            return unit.finalPosition().distance(cluster.centroid) > v;
        }

        public List<SwarmCluster> getClusters() {
            return clusters;
        }


        private record QuadrantParameters(int startX, int startY, int endX, int endY) {
        }

        /**
         * Remove all strategic goals on the quadrant of the given coordinates
         * @param coords The coordinates to check
         */
        public void removeAllStrategicGoalsOnCoordsQuadrant(Coords coords) {
            QuadrantParameters quadrant = getQuadrantParameters(coords);
            for (int i = quadrant.startX(); i < quadrant.endX(); i++) {
                for (int j = quadrant.startY(); j < quadrant.endY(); j++) {
                    strategicGoals.remove(new Coords(i, j));
                }
            }
        }

        /**
         * Initialize the strategic goals for the board
         * @param board The board to initialize the goals on
         * @param quadrantWidth The width of the quadrants
         * @param quadrantHeight The height of the quadrants
         */
        public void initializeStrategicGoals(Board board, int quadrantWidth, int quadrantHeight) {
            strategicGoals.clear();
            this.quadrantWidth = quadrantWidth;
            this.quadrantHeight = quadrantHeight;

            int boardWidth = board.getWidth();
            int boardHeight = board.getHeight();

            // Calculate extra space and offsets to center the quadrants
            int extraX = boardWidth % quadrantWidth;
            int extraY = boardHeight % quadrantHeight;
            offsetX = extraX / 2;
            offsetY = extraY / 2;

            // Iterate over each quadrant using the offsets
            for (int i = 0; i < (boardWidth - offsetX); i += quadrantWidth) {
                for (int j = 0; j < (boardHeight - offsetY); j += quadrantHeight) {
                    int startX = offsetX + i;
                    int startY = offsetY + j;
                    int endX = Math.min(startX + quadrantWidth, boardWidth);
                    int endY = Math.min(startY + quadrantHeight, boardHeight);

                    var xMidPoint = (startX + endX) / 2;
                    var yMidPoint = (startY + endY) / 2;
                    for (var coords : new Coords(xMidPoint, yMidPoint).allAtDistanceOrLess(3)) {
                        var hex = board.getHex(coords);
                        if (hex == null || hex.isClearHex() && hasNoHazards(hex)) {
                            addStrategicGoal(coords);
                            break;
                        }
                    }
                }
            }
        }

        private static final Set<Integer> HAZARDS = new HashSet<>(Arrays.asList(Terrains.FIRE,
            Terrains.MAGMA,
            Terrains.ICE,
            Terrains.WATER,
            Terrains.BUILDING,
            Terrains.BRIDGE,
            Terrains.BLACK_ICE,
            Terrains.SNOW,
            Terrains.SWAMP,
            Terrains.MUD,
            Terrains.TUNDRA));

        private boolean hasNoHazards(Hex hex) {
            var hazards = hex.getTerrainTypesSet();
            // Black Ice can appear if the conditions are favorable
            hazards.retainAll(HAZARDS);
            return hazards.isEmpty();
        }

        /**
         * Get the cluster for a unit
         * @param unit The unit to get the cluster for
         * @return The cluster for the unit, initializes a new cluster if it doesn't exist
         */
        public SwarmCluster getClusterFor(UnitState unit) {
            var cluster = unitClusters.get(unit.id());
            if (cluster == null) {
                cluster = new SwarmCluster();
                unitClusters.put(unit.id(), cluster);
                cluster.addMember(unit);
                clusterUnitsSize++;
            }
            return cluster;
        }

        public static class SwarmCluster {
            List<UnitState> members = new ArrayList<>();
            Coords centroid;
            int maxSize = 6;

            public List<UnitState> getMembers() {
                return members;
            }

            public Coords getCentroid() {
                return centroid;
            }

            public void addMember(UnitState unit) {
                if (members.size() >= maxSize) return;
                members.add(unit);
                updateCentroid();
            }

            private void updateCentroid() {
                if (members.isEmpty()) return;
                centroid = calculateClusterCentroid(members);
            }

            private Coords calculateClusterCentroid(List<UnitState> members) {
                double count = 0;
                double qSum = 0;
                double rSum = 0;
                double sSum = 0;

                for (UnitState unit : members) {
                    CubeCoords cube = unit.position().toCube();
                    qSum += cube.q;
                    rSum += cube.r;
                    sSum += cube.s;
                    count ++;
                }

                CubeCoords weightedCube = new CubeCoords(
                    qSum / count,
                    rSum / count,
                    sSum / count
                );

                return weightedCube.roundToNearestHex().toOffset();

            }
        }

        private int calculateOptimalClusterSize(int totalUnits) {
            if (totalUnits <= 4) return 4;
            if (totalUnits % 4 == 0) return 4;
            if (totalUnits % 5 == 0) return 5;
            return (totalUnits % 4) > (totalUnits % 5) ? 5 : 4;
        }

        // Cluster management methods

        /**
         * Assign units to clusters
         * @param allUnits The units to assign
         */
        public void assignClusters(Collection<UnitState> allUnits) {
            if (clusterUnitsSize == allUnits.size()){
                clusters.forEach(SwarmCluster::updateCentroid);
                return;
            }
            clusterUnitsSize = 0;
            clusters.clear();
            unitClusters.clear();
            int optimalSize = calculateOptimalClusterSize(allUnits.size());

            // Sort units by role for better distribution
            List<UnitState> sortedUnits = allUnits.stream()
                .sorted(Comparator.comparingInt(u -> u.role().ordinal()))
                .collect(Collectors.toList());

            // Create initial clusters
            while (!sortedUnits.isEmpty()) {
                SwarmCluster cluster = new SwarmCluster();
                for (int i = 0; i < optimalSize && !sortedUnits.isEmpty(); i++) {
                    var unit = sortedUnits.get(0);
                    unitClusters.put(unit.id(), cluster);
                    cluster.addMember(sortedUnits.remove(0));
                    clusterUnitsSize++;
                }
                clusters.add(cluster);
            }
        }
    }
}
