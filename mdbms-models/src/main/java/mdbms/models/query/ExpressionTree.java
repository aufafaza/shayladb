package mdbms.models.query;

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

    public String formatTree() {
        if (root == null) {
            return "(empty ExpressionTree)\n";
        }
        StringBuilder sb = new StringBuilder();
        formatNode(root, "", true, true, sb);
        return sb.toString();
    }

    public void printExpressionTree() {
        System.out.print(formatTree());
    }

    static void formatNode(
        ExpressionNode node,
        String prefix,
        boolean isRoot,
        boolean isLast,
        StringBuilder sb
    ) {
        if (node == null) {
            return;
        }
        if (!isRoot) {
            sb.append(prefix).append(isLast ? "└── " : "├── ");
        }
        sb.append(node.getType()).append(" (");
        if (node.getTableName() != null) {
            sb.append(node.getTableName()).append('.');
        }
        sb.append(node.getValue()).append(")\n");

        String childPrefix = isRoot ? prefix : prefix + (isLast ? "    " : "│   ");
        if (node.getLeft() != null && node.getRight() != null) {
            formatNode(node.getLeft(), childPrefix, false, false, sb);
            formatNode(node.getRight(), childPrefix, false, true, sb);
        } else if (node.getLeft() != null) {
            formatNode(node.getLeft(), childPrefix, false, true, sb);
        } else if (node.getRight() != null) {
            formatNode(node.getRight(), childPrefix, false, true, sb);
        }
    }
}
