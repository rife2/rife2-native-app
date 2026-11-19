package workorders;

public class ChecklistItem {
    private Integer id_;
    private int orderId_;
    private int position_;
    private String label_;
    private boolean done_;

    public Integer getId() { return id_; }
    public void setId(Integer id) { id_ = id; }
    public int getOrderId() { return orderId_; }
    public void setOrderId(int orderId) { orderId_ = orderId; }
    public int getPosition() { return position_; }
    public void setPosition(int position) { position_ = position; }
    public String getLabel() { return label_; }
    public void setLabel(String label) { label_ = label; }
    public boolean isDone() { return done_; }
    public void setDone(boolean done) { done_ = done; }
}
