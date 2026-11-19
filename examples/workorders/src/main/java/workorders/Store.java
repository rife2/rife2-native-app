package workorders;

import rife.database.Datasource;
import rife.database.DbQueryManager;
import rife.database.migrations.DbMigration;
import rife.database.migrations.DbMigrations;
import rife.database.querymanagers.generic.GenericQueryManager;
import rife.database.querymanagers.generic.GenericQueryManagerFactory;
import rife.config.RifeConfig;
import rife.database.exceptions.DatabaseException;
import rife.database.queries.Update;
import rife.resources.DatabaseResourcesFactory;
import rife.resources.exceptions.ResourceWriterErrorException;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * The work orders of one database: the office's, or the one on a
 * technician's device. Both have the same tables.
 */
public class Store {
    private final Datasource datasource_;
    private final GenericQueryManager<WorkOrder> orders_;
    private final GenericQueryManager<ChecklistItem> checklist_;
    private final GenericQueryManager<PartUsed> parts_;
    private final GenericQueryManager<Photo> photos_;

    public Store(Datasource datasource) {
        datasource_ = datasource;
        orders_ = GenericQueryManagerFactory.instance(datasource, WorkOrder.class);
        checklist_ = GenericQueryManagerFactory.instance(datasource, ChecklistItem.class);
        parts_ = GenericQueryManagerFactory.instance(datasource, PartUsed.class);
        photos_ = GenericQueryManagerFactory.instance(datasource, Photo.class);
    }

    public static Store h2(String path) {
        return new Store(new Datasource("org.h2.Driver", "jdbc:h2:" + path, "sa", "", 5));
    }

    public Datasource datasource() {
        return datasource_;
    }

    public void migrate() {
        // the version of the schema is kept in the database itself
        var state = DatabaseResourcesFactory.instance(datasource_);
        if (!tableExists(RifeConfig.resources().getTableResources())) {
            try {
                state.install();
            } catch (ResourceWriterErrorException e) {
                throw new DatabaseException(e);
            }
        }
        new DbMigrations(datasource_)
            .state(state)
            .add(1, new DbMigration() {
                public void up() {
                    add(manager -> {
                        orders_.install();
                        checklist_.install();
                        parts_.install();
                        photos_.install();
                    });
                }
            })
            .migrate();
    }

    private boolean tableExists(String table) {
        var connection = datasource_.getConnection();
        try (var tables = connection.getMetaData().getTables(null, null, table.toUpperCase(), null)) {
            return tables.next();
        } catch (SQLException e) {
            throw new DatabaseException(e);
        } finally {
            connection.close();
        }
    }

    public void close() {
        datasource_.close();
    }

    public void inTransaction(Runnable work) {
        new DbQueryManager(datasource_).inTransaction(work::run);
    }

    public <T> T inTransaction(Supplier<T> work) {
        return new DbQueryManager(datasource_).inTransaction(work::get);
    }

    /**
     * Changes the status of an order only when it still has the expected
     * one, so that of two requests that race for the same order, only one
     * goes ahead.
     */
    public boolean claim(int orderId, String expected, String status) {
        return new DbQueryManager(datasource_).executeUpdate(new Update(datasource_)
            .table(orders_.getTable())
            .field("status", status)
            .where("id", "=", orderId)
            .whereAnd("status", "=", expected)) == 1;
    }

    public WorkOrder order(int id) {
        return orders_.restore(id);
    }

    public WorkOrder orderFromOffice(int officeId) {
        return orders_.restoreFirst(orders_.getRestoreQuery().where("officeId", "=", officeId));
    }

    public List<WorkOrder> orders() {
        return orders_.restore(orders_.getRestoreQuery()
            .orderBy("scheduledFor").orderBy("arrival").orderBy("id"));
    }

    public List<WorkOrder> ordersWithStatus(String status) {
        return orders_.restore(orders_.getRestoreQuery().where("status", "=", status)
            .orderBy("scheduledFor").orderBy("arrival").orderBy("id"));
    }

    // also the ones of earlier days, which are overdue but still to be done
    public List<WorkOrder> scheduledFor(String technician) {
        return orders_.restore(orders_.getRestoreQuery()
            .where("technician", "=", technician)
            .whereAnd("status", "=", WorkOrder.SCHEDULED)
            .orderBy("scheduledFor").orderBy("arrival").orderBy("id"));
    }

    public boolean hasOrdersFrom(LocalDate day) {
        return orders_.count(orders_.getCountQuery().where("scheduledFor", ">=", day)) > 0;
    }

    public List<String> technicians() {
        var technicians = new TreeSet<String>();
        for (var order : orders()) {
            technicians.add(order.getTechnician());
        }
        return new ArrayList<>(technicians);
    }

    // a new order gets the checklist of its equipment
    public WorkOrder create(WorkOrder order) {
        inTransaction(() -> {
            orders_.save(order);
            if (null == order.getNumber()) {
                order.setNumber("WO-" + (1000 + order.getId()));
                orders_.save(order);
            }
            var position = 0;
            for (var label : Checklists.forEquipment(order.getEquipment())) {
                addChecklistItem(order.getId(), position++, label, false);
            }
        });
        return order;
    }

    public void save(WorkOrder order) {
        orders_.save(order);
    }

    public List<ChecklistItem> checklist(int orderId) {
        return checklist_.restore(checklist_.getRestoreQuery().where("orderId", "=", orderId).orderBy("position"));
    }

    public ChecklistItem checklistItem(int orderId, int itemId) {
        return checklist_.restoreFirst(checklist_.getRestoreQuery().where("id", "=", itemId).whereAnd("orderId", "=", orderId));
    }

    public void addChecklistItem(int orderId, int position, String label, boolean done) {
        var item = new ChecklistItem();
        item.setOrderId(orderId);
        item.setPosition(position);
        item.setLabel(label);
        item.setDone(done);
        checklist_.save(item);
    }

    public void save(ChecklistItem item) {
        checklist_.save(item);
    }

    public void clearChecklist(int orderId) {
        checklist_.delete(checklist_.getDeleteQuery().where("orderId", "=", orderId));
    }

    public void clearParts(int orderId) {
        parts_.delete(parts_.getDeleteQuery().where("orderId", "=", orderId));
    }

    public void clearPhotos(int orderId) {
        photos_.delete(photos_.getDeleteQuery().where("orderId", "=", orderId));
    }

    public List<PartUsed> parts(int orderId) {
        return parts_.restore(parts_.getRestoreQuery().where("orderId", "=", orderId).orderBy("id"));
    }

    public void add(PartUsed part) {
        parts_.save(part);
    }

    public boolean deletePart(int orderId, int partId) {
        return parts_.delete(parts_.getDeleteQuery().where("id", "=", partId).whereAnd("orderId", "=", orderId));
    }

    // without their data, which a page only needs to link to
    public List<Photo> photos(int orderId, String kind) {
        return photos_.restore(photos_.getRestoreQuery().fieldsExcluded(Photo.class, "data")
            .where("orderId", "=", orderId).whereAnd("kind", "=", kind).orderBy("id"));
    }

    public List<Photo> photosWithData(int orderId) {
        return photos_.restore(photos_.getRestoreQuery().where("orderId", "=", orderId).orderBy("id"));
    }

    public Photo photo(int id) {
        return photos_.restore(id);
    }

    public void add(Photo photo) {
        photos_.save(photo);
    }

    public void replaceSignature(Photo signature) {
        inTransaction(() -> {
            photos_.delete(photos_.getDeleteQuery().where("orderId", "=", signature.getOrderId()).whereAnd("kind", "=", Photo.SIGNATURE));
            photos_.save(signature);
        });
    }
}
