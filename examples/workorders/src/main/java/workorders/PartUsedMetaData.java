package workorders;

import rife.validation.ConstrainedProperty;
import rife.validation.MetaData;

public class PartUsedMetaData extends MetaData {
    public void activateMetaData() {
        addConstraint(new ConstrainedProperty("id").identifier(true).editable(false));
        addConstraint(new ConstrainedProperty("orderId").manyToOne(WorkOrder.class, "id").editable(false));
        addConstraint(new ConstrainedProperty("part").notNull(true).notEmpty(true).maxLength(80));
        addConstraint(new ConstrainedProperty("quantity").notNull(true).rangeBegin(1).rangeEnd(99));
    }
}
