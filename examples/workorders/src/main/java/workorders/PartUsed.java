package workorders;

public class PartUsed {
    private Integer id_;
    private int orderId_;
    private String part_;
    private Integer quantity_;

    public Integer getId() { return id_; }
    public void setId(Integer id) { id_ = id; }
    public int getOrderId() { return orderId_; }
    public void setOrderId(int orderId) { orderId_ = orderId; }
    public String getPart() { return part_; }
    public void setPart(String part) { part_ = part; }
    public Integer getQuantity() { return quantity_; }
    public void setQuantity(Integer quantity) { quantity_ = quantity; }
}
