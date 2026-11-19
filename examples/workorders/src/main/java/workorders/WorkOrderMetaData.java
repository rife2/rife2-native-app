package workorders;

import rife.validation.ConstrainedProperty;
import rife.validation.MetaData;

import java.text.SimpleDateFormat;

public class WorkOrderMetaData extends MetaData {
    public static final String[] EQUIPMENT = {"Boiler", "Heat pump", "Elevator", "Solar inverter"};
    public static final String[] ARRIVALS = {"08:00 to 10:00", "10:00 to 12:00", "13:00 to 15:00", "15:00 to 17:00"};
    // what the dispatcher fills in
    public static final String DISPATCH = "dispatch";
    // the longest name of who signs for the work
    public static final int SIGNER_LENGTH = 80;

    // what a date input sends
    private static SimpleDateFormat isoDate() {
        var format = new SimpleDateFormat("yyyy-MM-dd");
        format.setLenient(false);
        return format;
    }

    public void activateMetaData() {
        addConstraint(new ConstrainedProperty("id").identifier(true).editable(false));
        // a device has each order of the office once
        addConstraint(new ConstrainedProperty("officeId").editable(false).unique(true));
        addConstraint(new ConstrainedProperty("number").editable(false).maxLength(20));
        addConstraint(new ConstrainedProperty("status").editable(false).maxLength(20));
        addConstraint(new ConstrainedProperty("signedBy").editable(false).maxLength(SIGNER_LENGTH));
        addConstraint(new ConstrainedProperty("finishedAt").editable(false));
        addGroup(DISPATCH)
            .addConstraint(new ConstrainedProperty("customer").notNull(true).notEmpty(true).maxLength(80))
            .addConstraint(new ConstrainedProperty("address").notNull(true).notEmpty(true).maxLength(200))
            .addConstraint(new ConstrainedProperty("phone").maxLength(30))
            .addConstraint(new ConstrainedProperty("equipment").notNull(true).inList(EQUIPMENT))
            .addConstraint(new ConstrainedProperty("problem").notNull(true).notEmpty(true).maxLength(2000))
            .addConstraint(new ConstrainedProperty("technician").notNull(true).notEmpty(true).maxLength(40))
            .addConstraint(new ConstrainedProperty("scheduledFor").notNull(true).format(isoDate()))
            .addConstraint(new ConstrainedProperty("arrival").notNull(true).inList(ARRIVALS));
        // filled in by the sign-off, see Report
        addConstraint(new ConstrainedProperty("notes").maxLength(4000));
    }
}
