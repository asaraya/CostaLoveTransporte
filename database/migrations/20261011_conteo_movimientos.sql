-- Corrección no destructiva de procedimientos. Ejecutar sobre la base seleccionada.
-- No modifica ni borra paquetes o movimientos existentes.
SET NAMES utf8mb4;
DELIMITER ;;
DROP PROCEDURE IF EXISTS `sp_aplicar_status_externo` ;;
CREATE PROCEDURE `sp_aplicar_status_externo`(
  IN p_tracking VARCHAR(100),
  IN p_status VARCHAR(100),
  IN p_status_at DATETIME,
  IN p_changed_by VARCHAR(100)
)
BEGIN
  DECLARE v_id BIGINT UNSIGNED;
  DECLARE v_status_at DATETIME;
  DECLARE v_norm VARCHAR(200);
  DECLARE v_estado_actual VARCHAR(80);
  DECLARE v_prev_status_at DATETIME;
  DECLARE v_prev_delivered DATETIME;
  DECLARE v_prev_returned DATETIME;
  DECLARE v_is_newer BOOL;
  DECLARE v_mid BIGINT UNSIGNED;

  SET v_status_at = p_status_at;
  IF v_status_at IS NOT NULL THEN
    SET v_status_at = DATE_SUB(v_status_at, INTERVAL 6 HOUR);
  END IF;

  SELECT id, estado, status_externo_at, delivered_at, returned_at
  INTO v_id, v_estado_actual, v_prev_status_at, v_prev_delivered, v_prev_returned
  FROM paquetes
  WHERE tracking_code = p_tracking
  LIMIT 1
  FOR UPDATE;

  IF v_id IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Tracking no encontrado';
  END IF;

  SET v_norm = LOWER(TRIM(IFNULL(p_status,'')));
  SET v_is_newer = (v_prev_status_at IS NULL OR (v_status_at IS NOT NULL AND v_status_at >= v_prev_status_at));

  IF v_is_newer THEN
    UPDATE paquetes
    SET status_externo = p_status,
        status_externo_at = CASE WHEN v_status_at IS NOT NULL THEN v_status_at ELSE status_externo_at END
    WHERE id = v_id;
  END IF;

  IF v_is_newer AND v_status_at IS NOT NULL THEN

    IF v_norm LIKE 'tr a ca%'
       OR v_norm LIKE 'tr_a_ca%'
       OR v_norm LIKE 'tr ca%'
       OR v_norm LIKE 'tr-ca%'
    THEN
      IF v_estado_actual <> 'TR_A_CA' THEN
        UPDATE paquetes
        SET estado = 'TR_A_CA',
            delivered_at = NULL,
            returned_at = NULL,
            mensajero_id = NULL,
            last_state_change_at = v_status_at
        WHERE id = v_id;

        INSERT INTO paquete_estado_historial(paquete_id, estado_from, estado_to, changed_at, motivo, changed_by, mensajero_id)
        VALUES (v_id, v_estado_actual, 'TR_A_CA', v_status_at, 'AUTO: status externo', p_changed_by, NULL);

        SET v_estado_actual = 'TR_A_CA';
      ELSE
        UPDATE paquetes
        SET delivered_at = NULL,
            returned_at = NULL,
            mensajero_id = NULL,
            last_state_change_at = last_state_change_at
        WHERE id = v_id;
      END IF;

    ELSEIF v_norm LIKE 'entregado a transportista local 2do intento%'
       OR v_norm LIKE 'entregado a transportista local segundo intento%'
       OR v_norm LIKE 'delivered to local carrier 2nd attempt%'
    THEN
      IF v_estado_actual <> 'ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO' THEN
        UPDATE paquetes
        SET estado = 'ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO',
            delivered_at = NULL,
            returned_at = NULL,
            mensajero_id = NULL,
            last_state_change_at = v_status_at
        WHERE id = v_id;

        INSERT INTO paquete_estado_historial(paquete_id, estado_from, estado_to, changed_at, motivo, changed_by, mensajero_id)
        VALUES (v_id, v_estado_actual, 'ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO', v_status_at, 'AUTO: status externo', p_changed_by, NULL);

        SET v_estado_actual = 'ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO';
      ELSE
        UPDATE paquetes
        SET last_state_change_at = last_state_change_at
        WHERE id = v_id;
      END IF;

    ELSEIF v_norm LIKE 'entregado a transportista local%'
       OR v_norm LIKE 'delivered to local carrier%'
    THEN
      IF v_estado_actual <> 'ENTREGADO_A_TRANSPORTISTA_LOCAL' THEN
        UPDATE paquetes
        SET estado = 'ENTREGADO_A_TRANSPORTISTA_LOCAL',
            delivered_at = NULL,
            returned_at = NULL,
            mensajero_id = NULL,
            last_state_change_at = v_status_at
        WHERE id = v_id;

        INSERT INTO paquete_estado_historial(paquete_id, estado_from, estado_to, changed_at, motivo, changed_by, mensajero_id)
        VALUES (v_id, v_estado_actual, 'ENTREGADO_A_TRANSPORTISTA_LOCAL', v_status_at, 'AUTO: status externo', p_changed_by, NULL);

        SET v_estado_actual = 'ENTREGADO_A_TRANSPORTISTA_LOCAL';
      ELSE
        UPDATE paquetes
        SET last_state_change_at = last_state_change_at
        WHERE id = v_id;
      END IF;

    ELSEIF v_norm LIKE 'no entregado consignatario%'
       OR v_norm LIKE 'consignatario no disponible%'
       OR v_norm LIKE 'consignee not available%'
       OR v_norm LIKE 'recipient not available%'
    THEN
      IF v_estado_actual <> 'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE' THEN
        UPDATE paquetes
        SET estado = 'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE',
            delivered_at = NULL,
            returned_at = NULL,
            mensajero_id = NULL,
            last_state_change_at = v_status_at
        WHERE id = v_id;

        INSERT INTO paquete_estado_historial(paquete_id, estado_from, estado_to, changed_at, motivo, changed_by, mensajero_id)
        VALUES (v_id, v_estado_actual, 'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE', v_status_at, 'AUTO: status externo', p_changed_by, NULL);

        SET v_estado_actual = 'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE';
      ELSE
        UPDATE paquetes
        SET last_state_change_at = last_state_change_at
        WHERE id = v_id;
      END IF;

    ELSEIF v_norm LIKE 'prueba de entrega%'
       OR v_norm LIKE 'proof of delivery%'
       OR v_norm LIKE 'pod%'
    THEN
      SELECT id INTO v_mid FROM usuarios WHERE rol = 'MENSAJERO' ORDER BY id LIMIT 1;

      IF v_mid IS NULL THEN
        INSERT INTO usuarios (username, full_name, password, role, active, rol)
        SELECT 'mensajero_sistema', 'Mensajero Sistema', 'NOLOGIN', 'USER', 0, 'MENSAJERO'
        FROM dual
        WHERE NOT EXISTS (SELECT 1 FROM usuarios WHERE username = 'mensajero_sistema');
        SELECT id INTO v_mid FROM usuarios WHERE rol = 'MENSAJERO' ORDER BY id LIMIT 1;
      END IF;

      IF v_mid IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'No existe usuario con rol MENSAJERO';
      END IF;

      IF v_estado_actual <> 'PRUEBA_DE_ENTREGA' THEN
        UPDATE paquetes
        SET estado = 'PRUEBA_DE_ENTREGA',
            delivered_at = v_status_at,
            returned_at = NULL,
            mensajero_id = COALESCE(mensajero_id, v_mid),
            last_state_change_at = v_status_at
        WHERE id = v_id;

        INSERT INTO paquete_estado_historial(paquete_id, estado_from, estado_to, changed_at, motivo, changed_by, mensajero_id)
        VALUES (v_id, v_estado_actual, 'PRUEBA_DE_ENTREGA', v_status_at, 'AUTO: status externo', p_changed_by, v_mid);

        SET v_estado_actual = 'PRUEBA_DE_ENTREGA';
      ELSE
        UPDATE paquetes
        SET delivered_at = COALESCE(delivered_at, v_status_at),
            returned_at = NULL,
            mensajero_id = COALESCE(mensajero_id, v_mid),
            last_state_change_at = last_state_change_at
        WHERE id = v_id;
      END IF;

    ELSEIF v_norm LIKE 'en transito a bodegas%'
       OR v_norm LIKE 'en tránsito a bodegas%'
       OR v_norm LIKE 'devolucion%'
       OR v_norm LIKE 'devolución%'
       OR v_norm LIKE 'devoluciones%'
       OR v_norm LIKE 'devuelto%'
       OR v_norm LIKE 'almacenaje%'
       OR v_norm LIKE 'in transit to warehouse%'
       OR v_norm LIKE 'in transit to warehouses%'
       OR v_norm LIKE 'return%'
       OR v_norm LIKE 'returned%'
       OR v_norm LIKE 'storage%'
       OR v_norm LIKE 'retornado a oficina local%'
       OR v_norm LIKE 'returned to local office%'
       OR v_norm LIKE 'no entregable%'
    THEN
      IF v_estado_actual <> 'NO_ENTREGABLE' THEN
        UPDATE paquetes
        SET estado = 'NO_ENTREGABLE',
            devolucion_subtipo = COALESCE(devolucion_subtipo, 'FUERA_DE_RUTA'),
            delivered_at = NULL,
            returned_at = v_status_at,
            mensajero_id = NULL,
            last_state_change_at = v_status_at
        WHERE id = v_id;

        INSERT INTO paquete_estado_historial(paquete_id, estado_from, estado_to, changed_at, motivo, changed_by, mensajero_id)
        VALUES (v_id, v_estado_actual, 'NO_ENTREGABLE', v_status_at, 'AUTO: status externo', p_changed_by, NULL);

        SET v_estado_actual = 'NO_ENTREGABLE';
      ELSE
        UPDATE paquetes
        SET devolucion_subtipo = COALESCE(devolucion_subtipo, 'FUERA_DE_RUTA'),
            delivered_at = NULL,
            returned_at = COALESCE(returned_at, v_status_at),
            last_state_change_at = last_state_change_at
        WHERE id = v_id;
      END IF;

    ELSE
      UPDATE paquetes
      SET last_state_change_at = last_state_change_at
      WHERE id = v_id;
    END IF;

  END IF;

  SELECT * FROM vw_paquete_resumen WHERE id = v_id;
END ;;

DROP PROCEDURE IF EXISTS `sp_set_marchamo_distrito` ;;
CREATE PROCEDURE `sp_set_marchamo_distrito`(
  IN p_tracking VARCHAR(100),
  IN p_marchamo VARCHAR(100),
  IN p_distrito_nombre VARCHAR(100),
  IN p_received_at DATETIME,
  IN p_changed_by VARCHAR(100)
)
BEGIN
  DECLARE v_id   BIGINT UNSIGNED;
  DECLARE v_saco BIGINT UNSIGNED;
  DECLARE v_dist BIGINT UNSIGNED;

  SELECT id INTO v_id FROM paquetes WHERE tracking_code = p_tracking LIMIT 1 FOR UPDATE;
  IF v_id IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Tracking no existe en paquetes'; END IF;

  SELECT id INTO v_saco FROM sacos WHERE marchamo = p_marchamo LIMIT 1;
  IF v_saco IS NULL THEN
    INSERT INTO sacos(marchamo) VALUES (p_marchamo);
    SET v_saco = LAST_INSERT_ID();
  END IF;

  SELECT id INTO v_dist FROM distritos WHERE nombre = p_distrito_nombre LIMIT 1;
  IF v_dist IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Distrito no existe'; END IF;

  UPDATE paquetes
     SET saco_id = v_saco,
         distrito_id = v_dist,
         received_at = COALESCE(p_received_at, received_at)
   WHERE id = v_id;

  INSERT INTO paquete_estado_historial(paquete_id, estado_from, estado_to, changed_at, motivo, changed_by, mensajero_id)
  SELECT v_id, estado, estado, CURRENT_TIMESTAMP,
         CONCAT('SET MARCHAMO=', p_marchamo, ' DISTRITO=', p_distrito_nombre),
         p_changed_by, mensajero_id
    FROM paquetes
   WHERE id = v_id;

  SELECT * FROM vw_paquete_resumen WHERE id = v_id;
END ;;

DROP PROCEDURE IF EXISTS `sp_set_marchamo_ubicacion` ;;
CREATE PROCEDURE `sp_set_marchamo_ubicacion`(
  IN p_tracking VARCHAR(100),
  IN p_marchamo VARCHAR(100),
  IN p_ubicacion_codigo VARCHAR(100),
  IN p_received_at DATETIME,
  IN p_changed_by VARCHAR(100)
)
BEGIN
  DECLARE v_id   BIGINT UNSIGNED;
  DECLARE v_saco BIGINT UNSIGNED;
  DECLARE v_ubic BIGINT UNSIGNED;

  SELECT id INTO v_id FROM paquetes WHERE tracking_code = p_tracking LIMIT 1 FOR UPDATE;
  IF v_id IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Tracking no existe en paquetes'; END IF;

  SELECT id INTO v_saco FROM sacos WHERE marchamo = p_marchamo LIMIT 1;
  IF v_saco IS NULL THEN
    INSERT INTO sacos(marchamo) VALUES (p_marchamo);
    SET v_saco = LAST_INSERT_ID();
  END IF;

  SELECT id INTO v_ubic FROM ubicacion WHERE codigo = p_ubicacion_codigo LIMIT 1;
  IF v_ubic IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Ubicación no existe'; END IF;

  UPDATE paquetes
     SET saco_id = v_saco,
         ubicacion_id = v_ubic,
         received_at = COALESCE(p_received_at, received_at)
   WHERE id = v_id;

  INSERT INTO paquete_estado_historial(paquete_id, estado_from, estado_to, changed_at, motivo, changed_by, mensajero_id)
  SELECT v_id, estado, estado, CURRENT_TIMESTAMP,
         CONCAT('SET MARCHAMO=', p_marchamo, ' UBIC=', p_ubicacion_codigo),
         p_changed_by, mensajero_id
    FROM paquetes
   WHERE id = v_id;

  SELECT * FROM vw_paquete_resumen WHERE id = v_id;
END ;;

DELIMITER ;
