package workorders;

import rife.validation.ConstrainedProperty;
import rife.validation.MetaData;

public class PhotoMetaData extends MetaData {
    public void activateMetaData() {
        addConstraint(new ConstrainedProperty("id").identifier(true));
        addConstraint(new ConstrainedProperty("orderId").manyToOne(WorkOrder.class, "id"));
        addConstraint(new ConstrainedProperty("kind").notNull(true).inList(Photo.PHOTO, Photo.SIGNATURE));
        addConstraint(new ConstrainedProperty("contentType").notNull(true).maxLength(50));
        addConstraint(new ConstrainedProperty("data").notNull(true));
    }
}
