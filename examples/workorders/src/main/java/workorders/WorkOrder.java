package workorders;

import java.time.Instant;
import java.time.LocalDate;

public class WorkOrder {
    public static final String SCHEDULED = "scheduled";
    public static final String STARTED = "started";
    public static final String FINISHED = "finished";
    // finished on the device and received by the office
    public static final String REPORTED = "reported";

    private Integer id_;
    private Integer officeId_;
    private String number_;
    private String customer_;
    private String address_;
    private String phone_;
    private String equipment_;
    private String problem_;
    private String technician_;
    private LocalDate scheduledFor_;
    private String arrival_;
    private String status_ = SCHEDULED;
    private String notes_;
    private Integer minutes_;
    private String signedBy_;
    private Instant finishedAt_;

    public Integer getId() { return id_; }
    public void setId(Integer id) { id_ = id; }
    public Integer getOfficeId() { return officeId_; }
    public void setOfficeId(Integer officeId) { officeId_ = officeId; }
    public String getNumber() { return number_; }
    public void setNumber(String number) { number_ = number; }
    public String getCustomer() { return customer_; }
    public void setCustomer(String customer) { customer_ = customer; }
    public String getAddress() { return address_; }
    public void setAddress(String address) { address_ = address; }
    public String getPhone() { return phone_; }
    public void setPhone(String phone) { phone_ = phone; }
    public String getEquipment() { return equipment_; }
    public void setEquipment(String equipment) { equipment_ = equipment; }
    public String getProblem() { return problem_; }
    public void setProblem(String problem) { problem_ = problem; }
    public String getTechnician() { return technician_; }
    public void setTechnician(String technician) { technician_ = technician; }
    public LocalDate getScheduledFor() { return scheduledFor_; }
    public void setScheduledFor(LocalDate scheduledFor) { scheduledFor_ = scheduledFor; }
    public String getArrival() { return arrival_; }
    public void setArrival(String arrival) { arrival_ = arrival; }
    public String getStatus() { return status_; }
    public void setStatus(String status) { status_ = status; }
    public String getNotes() { return notes_; }
    public void setNotes(String notes) { notes_ = notes; }
    public Integer getMinutes() { return minutes_; }
    public void setMinutes(Integer minutes) { minutes_ = minutes; }
    public String getSignedBy() { return signedBy_; }
    public void setSignedBy(String signedBy) { signedBy_ = signedBy; }
    public Instant getFinishedAt() { return finishedAt_; }
    public void setFinishedAt(Instant finishedAt) { finishedAt_ = finishedAt; }

    public boolean isDone() {
        return FINISHED.equals(status_) || REPORTED.equals(status_);
    }
}
