package mdbms.optimizer;

public enum TokenType {
    // Literals
    intLitTok,
    realLitTok,
    strTok,

    // Logical and arithmetic operators
    notTok,
    plus,
    minus,
    star,
    idiv,
    rdiv,
    imod,
    andTok,
    orTok,

    // Relational operators
    eql,
    neq,
    gtr,
    geq,
    lss,
    leq,

    // Delimiters and punctuation
    lparent,
    rparent,
    comma,
    semicolon,
    period,
    colon,

    // Identifiers
    idTok,

    // SQL keywords
    selectTok,
    fromTok,
    whereTok,
    insertTok,
    intoTok,
    valuesTok,
    deleteTok,
    updateTok,
    setTok,
    createTok,
    tableTok,
    viewTok,
    asTok,
    indexTok,
    onTok,
    intTypeTok,
    varcharTok,
    ifTok,
    existsTok,
    nullTok,
    primaryTok,
    keyTok,

    // Transaction keywords
    startTok,
    transactionTok,
    beginTok,
    commitTok,
    rollbackTok,

    EOF,
    UNKNOWN
}
