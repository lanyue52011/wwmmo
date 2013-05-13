package au.com.codeka.warworlds.server.ctrl;

import java.sql.Statement;

import au.com.codeka.common.model.BaseBuildRequest;
import au.com.codeka.common.model.BaseBuilding;
import au.com.codeka.common.model.BuildingDesign;
import au.com.codeka.common.model.Design;
import au.com.codeka.common.model.DesignKind;
import au.com.codeka.common.protobuf.Messages;
import au.com.codeka.warworlds.server.RequestException;
import au.com.codeka.warworlds.server.data.DB;
import au.com.codeka.warworlds.server.data.SqlStmt;
import au.com.codeka.warworlds.server.data.Transaction;
import au.com.codeka.warworlds.server.model.BuildRequest;
import au.com.codeka.warworlds.server.model.Colony;
import au.com.codeka.warworlds.server.model.DesignManager;
import au.com.codeka.warworlds.server.model.Star;

public class BuildQueueController {
    public void build(BuildRequest buildRequest) throws RequestException {
        try (Transaction t = DB.beginTransaction()) {
            Star star = new StarController(t).getStar(buildRequest.getStarID());
            Colony colony = star.getColony(buildRequest.getColonyID());

            Design design = DesignManager.i.getDesign(buildRequest.getDesignKind(), buildRequest.getDesignID());

            // check dependencies
            for (Design.Dependency dependency : design.getDependencies()) {
                if (!dependency.isMet(colony)) {
                    throw new RequestException(400, Messages.GenericError.ErrorCode.CannotBuildDependencyNotMet,
                            String.format("Cannot build %s as level %d %s is required.",
                                          buildRequest.getDesign().getDisplayName(),
                                          dependency.getLevel(),
                                          DesignManager.i.getDesign(DesignKind.BUILDING, dependency.getDesignID()).getDisplayName()));
                }
            }

            // check build limits
            if (design.getDesignKind() == DesignKind.BUILDING && buildRequest.getExistingBuildingKey() == null) {
                BuildingDesign buildingDesign = (BuildingDesign) design;
                if (buildingDesign.getMaxPerColony() > 0) {
                    int maxPerColony = buildingDesign.getMaxPerColony();
                    int numThisColony = 0;
                    for (BaseBuilding building : colony.getBuildings()) {
                        if (building.getDesignID().equals(buildRequest.getDesignID())) {
                            numThisColony ++;
                        }
                    }
                    for (BaseBuildRequest baseBuildRequest : star.getBuildRequests()) {
                        BuildRequest otherBuildRequest = (BuildRequest) baseBuildRequest;
                        if (otherBuildRequest.getColonyID() == colony.getID() &&
                            otherBuildRequest.getDesignID().equals(buildRequest.getDesignID())) {
                            numThisColony ++;
                        }
                    }

                    if (numThisColony >= maxPerColony) {
                        throw new RequestException(400, Messages.GenericError.ErrorCode.CannotBuildMaxPerColonyReached,
                                String.format("Cannot build %s, maximum per colony reached.",
                                              buildRequest.getDesign().getDisplayName()));
                    }
                }

                if (buildingDesign.getMaxPerEmpire() > 0) {
                    SqlStmt stmt = t.prepare("SELECT (" +
                                            "   SELECT COUNT(*)" +
                                            "   FROM buildings" +
                                            "   WHERE empire_id = ?" +
                                            "     AND design_id = ?" +
                                            " ) + (" +
                                            "   SELECT COUNT(*)" +
                                            "   FROM build_requests" +
                                            "   WHERE empire_id = ?" +
                                            "     AND design_id = ?" +
                                            " )");
                    stmt.setInt(1, buildRequest.getEmpireID());
                    stmt.setString(2, buildRequest.getDesignID());
                    stmt.setInt(3, buildRequest.getEmpireID());
                    stmt.setString(4, buildRequest.getDesignID());
                    Long numPerEmpire = stmt.selectFirstValue(Long.class);
                    if (numPerEmpire >= buildingDesign.getMaxPerEmpire()) {
                        throw new RequestException(400, Messages.GenericError.ErrorCode.CannotBuildMaxPerColonyReached,
                                String.format("Cannot build %s, maximum per empire reached.",
                                              buildRequest.getDesign().getDisplayName()));
                    }
                }
            }

            if (buildRequest.getCount() > 5000) {
                buildRequest.setCount(5000);
            }

            SqlStmt stmt = t.prepare("INSERT INTO build_requests (star_id, planet_index, colony_id, empire_id," +
                                           " existing_building_id, design_kind, design_id," +
                                           " count, progress, start_time, end_time)" +
                                         " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                    Statement.RETURN_GENERATED_KEYS);
            stmt.setInt(1, buildRequest.getStarID());
            stmt.setInt(2, buildRequest.getPlanetIndex());
            stmt.setInt(3, buildRequest.getColonyID());
            stmt.setInt(4, buildRequest.getEmpireID());
            if (buildRequest.getExistingBuildingKey() != null) {
                stmt.setInt(5, buildRequest.getExistingBuildingID());
            } else {
                stmt.setNull(5);
            }
            stmt.setInt(6, buildRequest.getDesignKind().getValue());
            stmt.setString(7, buildRequest.getDesignID());
            stmt.setInt(8, buildRequest.getCount());
            stmt.setDouble(9, buildRequest.getProgress(false));
            stmt.setDateTime(10, buildRequest.getStartTime());
            stmt.setDateTime(11, buildRequest.getEndTime());
            stmt.update();
            buildRequest.setID(stmt.getAutoGeneratedID());

            t.commit();
        } catch(Exception e) {
            throw new RequestException(e);
        }
    }

    public void stop(Star star, BuildRequest buildRequest) throws RequestException {
        star.getBuildRequests().remove(buildRequest);

        String sql = "DELETE FROM build_requests WHERE id = ?";
        try (SqlStmt stmt = DB.prepare(sql)) {
            stmt.setInt(1, buildRequest.getID());
            stmt.update();
        } catch(Exception e) {
            throw new RequestException(e);
        }
    }

    public void accelerate(Star star, BuildRequest buildRequest, float accelerateAmount) throws RequestException {
        float remainingProgress = 1.0f - buildRequest.getProgress(false);
        float progressToComplete = remainingProgress * accelerateAmount;

        Design design = buildRequest.getDesign();
        float mineralsToUse = design.getBuildCost().getCostInMinerals() * progressToComplete;
        float cost = mineralsToUse * buildRequest.getCount();

        Messages.CashAuditRecord.Builder audit_record_pb = Messages.CashAuditRecord.newBuilder();
        audit_record_pb.setEmpireId(buildRequest.getEmpireID());
        audit_record_pb.setBuildDesignId(buildRequest.getDesignID());
        audit_record_pb.setBuildCount(buildRequest.getCount());
        audit_record_pb.setAccelerateAmount(accelerateAmount);
        if (!new EmpireController().withdrawCash(buildRequest.getEmpireID(), cost, audit_record_pb)) {
            throw new RequestException(400, Messages.GenericError.ErrorCode.InsufficientCash,
                    "You don't have enough cash to accelerate this build.");
        }
        buildRequest.setProgress(buildRequest.getProgress(false) + progressToComplete);
    }
}