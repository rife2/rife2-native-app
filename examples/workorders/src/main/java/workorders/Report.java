package workorders;

import rife.validation.ConstrainedProperty;
import rife.validation.Validation;

// what the technician writes when signing off
public class Report extends Validation {
    private String notes_;
    private Integer minutes_;

    protected void activateValidation() {
        addConstraint(new ConstrainedProperty("notes").notNull(true).notEmpty(true).maxLength(4000));
        addConstraint(new ConstrainedProperty("minutes").notNull(true).rangeBegin(5).rangeEnd(720));
    }

    public String getNotes() { return notes_; }
    public void setNotes(String notes) { notes_ = notes; }
    public Integer getMinutes() { return minutes_; }
    public void setMinutes(Integer minutes) { minutes_ = minutes; }
}
