package com.rinkmetrics.account;

/**
 * Perfiles que puede tener un usuario. A futuro se compran (un usuario puede sumar varios); por ahora el registro
 * ofrece solo {@link #CLUBFPP} y {@link #PLAYERFPP}, y uno excluye al otro.
 */
public enum Perfil {
    CLUBFPP, CLUBFSP, CLUBCNT, PLAYERFPP, PLAYERFSP, PLAYERCNT;

    /** ¿Se puede elegir en la pantalla de registro? */
    public boolean registrable() {
        return this == CLUBFPP || this == PLAYERFPP;
    }

    public boolean player() {
        return name().startsWith("PLAYER");
    }
}
