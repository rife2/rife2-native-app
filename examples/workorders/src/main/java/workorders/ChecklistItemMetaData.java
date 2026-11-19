package workorders;

import rife.validation.ConstrainedProperty;
import rife.validation.MetaData;

public class ChecklistItemMetaData extends MetaData {
    public void activateMetaData() {
        addConstraint(new ConstrainedProperty("id").identifier(true));
        addConstraint(new ConstrainedProperty("orderId").manyToOne(WorkOrder.class, "id"));
        addConstraint(new ConstrainedProperty("label").notNull(true).maxLength(200));
    }
}
