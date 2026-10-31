package mdbms.models;

public class ExpressionTree {
    private ExpressionNode root;

    public ExpressionTree(ExpressionNode root) {
        this.root = root;
    }

    public ExpressionNode getRoot() {
        return root;
    }

    public void setRoot(ExpressionNode root) {
        this.root = root;
    }
}
